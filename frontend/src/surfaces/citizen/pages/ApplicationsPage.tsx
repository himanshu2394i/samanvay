import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { useCitizen } from '../CitizenContext'
import { formatDate, humanize } from '../lib/format'
import { applicationStatus } from '../lib/status'

export function ApplicationsPage() {
  const api = useCitizenApi()
  const { citizenId } = useCitizen()
  const navigate = useNavigate()
  const [ref, setRef] = useState('')

  const data = useAsync(
    async () => {
      const [apps, journeys] = await Promise.all([api.listApplications(citizenId ?? ''), api.listJourneys().catch(() => [])])
      return { apps, names: new Map(journeys.map((j) => [j.code, j.name])) }
    },
    citizenId ?? '',
  )

  function track(e: FormEvent) {
    e.preventDefault()
    const value = ref.trim()
    if (value) void navigate(`/applications/${encodeURIComponent(value)}`)
  }

  return (
    <section aria-labelledby="apps-h">
      <h1 id="apps-h">My applications</h1>
      {data.status === 'loading' ? <Loading label="Loading your applications" /> : null}
      {data.status === 'error' ? <ErrorNotice error={data.error} onRetry={data.reload} /> : null}
      {data.status === 'success' ? (
        data.data.apps.length === 0 ? (
          <div className="card narrow">
            <h2>Nothing here yet</h2>
            <p>You have not applied for anything yet. When you do, you can track every department check here.</p>
            <div className="actions">
              <Link className="btn primary" to="/services">
                Browse services
              </Link>
            </div>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Your applications</caption>
              <thead>
                <tr>
                  <th scope="col">Application number</th>
                  <th scope="col">Service</th>
                  <th scope="col">Status</th>
                  <th scope="col">Decision due</th>
                </tr>
              </thead>
              <tbody>
                {data.data.apps.map((a) => {
                  const st = applicationStatus(a.status)
                  return (
                    <tr key={a.referenceNo}>
                      <td>
                        <Link to={`/applications/${encodeURIComponent(a.referenceNo)}`}>{a.referenceNo}</Link>
                      </td>
                      <td>{data.data.names.get(a.journeyCode) ?? humanize(a.journeyCode)}</td>
                      <td>
                        <Badge tone={st.tone}>{st.label.split(':')[0]}</Badge>
                      </td>
                      <td>{formatDate(a.slaDueAt) || 'n/a'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}

      <form className="inline-form" onSubmit={track}>
        <Field label="Track by application number" value={ref} onChange={(e) => setRef(e.target.value)} required />
        <button type="submit" className="btn">
          Track
        </button>
      </form>
    </section>
  )
}
