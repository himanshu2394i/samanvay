import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { formatDateTime, shortId } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { appStatus, isOpenApplication, SLA_BADGE, slaState } from '../lib/status'

export function ApplicationsReviewPage() {
  const api = useStaffApi()
  const navigate = useNavigate()
  // `loadedAt` is taken when the data arrives (not during render) so SLA badges are stable between renders.
  const apps = useAsync(async () => ({ list: await api.listApplications(50), loadedAt: Date.now() }), 'applications')
  const [ref, setRef] = useState('')
  const [journey, setJourney] = useState('')
  const [openOnly, setOpenOnly] = useState(false)

  const journeys = useMemo(() => (apps.status === 'success' ? [...new Set(apps.data.list.map((a) => a.journeyCode))].sort() : []), [apps])
  const rows = useMemo(
    () =>
      apps.status === 'success'
        ? apps.data.list.filter((a) => (!journey || a.journeyCode === journey) && (!openOnly || isOpenApplication(a.status)))
        : [],
    [apps, journey, openOnly],
  )

  function open(e: FormEvent) {
    e.preventDefault()
    const value = ref.trim()
    if (value) void navigate(`/staff/officer/applications/${encodeURIComponent(value)}`)
  }

  return (
    <section aria-labelledby="apps-h">
      <h1 id="apps-h">Applications</h1>
      <p className="lede">The most recent applications across citizens. Open one to review its department checks and the records received.</p>

      <form className="inline-form" onSubmit={open}>
        <Field label="Open by application number" value={ref} onChange={(e) => setRef(e.target.value)} required />
        <button type="submit" className="btn">
          Open
        </button>
      </form>

      <div className="filters">
        <div className="field">
          <label htmlFor="journey-filter">Service</label>
          <select id="journey-filter" value={journey} onChange={(e) => setJourney(e.target.value)}>
            <option value="">All services</option>
            {journeys.map((j) => (
              <option key={j} value={j}>
                {j}
              </option>
            ))}
          </select>
        </div>
        <label className="check">
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} /> Open applications only
        </label>
        <button type="button" className="btn" onClick={apps.reload}>
          Refresh
        </button>
      </div>

      {apps.status === 'loading' ? <Loading label="Loading applications" /> : null}
      {apps.status === 'error' ? <ErrorNotice error={apps.error} onRetry={apps.reload} /> : null}
      {apps.status === 'success' ? (
        rows.length === 0 ? (
          <p>{apps.data.list.length === 0 ? 'No applications yet.' : 'No applications match these filters.'}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Applications</caption>
              <thead>
                <tr>
                  <th scope="col">Application</th>
                  <th scope="col">Service</th>
                  <th scope="col">Status</th>
                  <th scope="col">SLA due</th>
                  <th scope="col">Citizen</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((a) => {
                  const st = appStatus(a.status)
                  const sla = slaState(a.slaDueAt, isOpenApplication(a.status), apps.data.loadedAt)
                  return (
                    <tr key={a.referenceNo}>
                      <td>
                        <Link to={`/staff/officer/applications/${encodeURIComponent(a.referenceNo)}`}>{a.referenceNo}</Link>
                      </td>
                      <td className="mono">{a.journeyCode}</td>
                      <td>
                        <Badge tone={st.tone}>{st.label}</Badge>
                      </td>
                      <td>
                        {formatDateTime(a.slaDueAt) || 'n/a'}{' '}
                        {sla !== 'none' ? <Badge tone={SLA_BADGE[sla].tone}>{SLA_BADGE[sla].label}</Badge> : null}
                      </td>
                      <td className="mono" title={a.citizenId}>
                        {shortId(a.citizenId)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}
    </section>
  )
}
