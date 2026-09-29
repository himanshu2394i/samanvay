import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { ApiError } from '../../../api/client'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { StatusStepper } from '../../../ui/StatusStepper'
import { useAsync } from '../../../ui/useAsync'
import { formatDate, humanize } from '../lib/format'
import { applicationStatus, stepStatus } from '../lib/status'

/** How often an application that is still in progress is re-checked. */
export const REFRESH_MS = 8000

export function ApplicationPage() {
  const { ref = '' } = useParams()
  const api = useCitizenApi()
  const data = useAsync(
    async () => {
      const [application, steps, records] = await Promise.all([
        api.getApplication(ref),
        api.getApplicationSteps(ref),
        // A preview that fails must not hide the application itself.
        api.getIssuedRecords(ref).catch(() => null),
      ])
      return { application, steps, records }
    },
    ref,
  )
  const { reload } = data
  const final = data.status === 'success' && applicationStatus(data.data.application.status).final

  // Keep an in-progress application fresh; stop once it reaches a final state.
  const watching = data.status === 'success' && !final
  useEffect(() => {
    if (!watching) return
    const t = setInterval(reload, REFRESH_MS)
    return () => clearInterval(t)
  }, [watching, reload])

  if (data.status === 'loading') return <Loading label="Loading application" />
  if (data.status === 'error') {
    const notFound = data.error instanceof ApiError && data.error.status === 404
    return (
      <section className="card narrow">
        <h1>Application not found</h1>
        {notFound ? (
          <p>No application with number <code>{ref}</code> was found for you.</p>
        ) : (
          <ErrorNotice error={data.error} onRetry={data.reload} />
        )}
        <Link className="btn" to="/applications">
          My applications
        </Link>
      </section>
    )
  }

  const { application, steps, records } = data.data
  const st = applicationStatus(application.status)
  return (
    <section aria-labelledby="app-h">
      <p>
        <Link to="/applications">My applications</Link>
      </p>
      <h1 id="app-h">
        Application <span className="mono">{application.referenceNo}</span>
      </h1>
      <StatusStepper status={application.status} />
      <p className={`notice ${st.tone}`} role="status">
        {st.label}
      </p>
      <dl className="facts">
        <dt>Service</dt>
        <dd>{humanize(application.journeyCode)}</dd>
        <dt>Submitted</dt>
        <dd>{formatDate(application.submittedAt) || 'n/a'}</dd>
        <dt>Decision due</dt>
        <dd>{formatDate(application.slaDueAt) || 'n/a'}</dd>
      </dl>

      <h2>Department checks</h2>
      {steps.length === 0 ? (
        <p>Department checks will appear here shortly.</p>
      ) : (
        <ol className="timeline">
          {steps.map((s) => {
            const ss = stepStatus(s.status)
            return (
              <li key={`${s.stepCode}:${s.departmentCode}`}>
                <div>
                  <strong>{humanize(s.stepCode)}</strong> <span className="hint">from {humanize(s.departmentCode)}</span>
                </div>
                <Badge tone={ss.tone}>{ss.label}</Badge>
                {s.completedAt ? <span className="hint"> Received {formatDate(s.completedAt)}</span> : null}
              </li>
            )
          })}
        </ol>
      )}
      {records && records.length > 0 ? (
        <>
          <h2>Records fetched for you</h2>
          <p className="hint">
            Shown as the departments hold them right now. Samanvay does not keep a copy. Development build: these come
            from mock department systems.
          </p>
          <ul className="stack">
            {records.map((r) => (
              <li key={`${r.stepCode}:${r.departmentCode}`} className="card">
                <h3>{r.title}</h3>
                <p className="hint">
                  {r.issuer} ({r.liveSystem})
                </p>
                {r.fields.length > 0 ? (
                  <dl className="facts">
                    {r.fields.map((f) => (
                      <div key={f.label} className="fact">
                        <dt>{f.label}</dt>
                        <dd>{f.value}</dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <p>Waiting for this department system.</p>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <div className="actions">
        <button type="button" className="btn" onClick={reload} disabled={data.refreshing}>
          {data.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
        {watching ? <span className="hint">This page refreshes itself while your application is in progress.</span> : null}
      </div>
    </section>
  )
}
