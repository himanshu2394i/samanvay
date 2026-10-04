import { Link, useParams } from 'react-router-dom'
import { useAsync } from '../../ui/useAsync'
import { PortalError } from '../api'
import { usePortal } from '../context'
import { formatDate, humanize, keyLabel } from '../format'
import { ErrorNotice, Loading, StatusBadge } from '../ui'
import { NotFoundPage } from './NotFound'

function show(v: unknown): string {
  if (v === null || v === undefined) return ''
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

/** A received record. We do not know its shape, so show whatever key and value pairs it has. */
function RecordView({ record }: { record: unknown }) {
  if (record && typeof record === 'object' && !Array.isArray(record)) {
    return (
      <dl>
        {Object.entries(record).map(([k, v]) => (
          <div key={k}>
            <dt>{keyLabel(k)}</dt>
            <dd>{show(v)}</dd>
          </div>
        ))}
      </dl>
    )
  }
  return <p>{show(record)}</p>
}

export function ApplicationDetailPage() {
  const { ref = '' } = useParams()
  const { api } = usePortal()
  const app = useAsync(() => api.application(ref), `app:${ref}`)
  const steps = useAsync(() => api.steps(ref), `steps:${ref}`)
  const records = useAsync(() => api.records(ref), `records:${ref}`)

  if (app.status === 'loading') return <Loading label="Loading your application" rows={4} />
  if (app.status === 'error') {
    return app.error instanceof PortalError && app.error.status === 404 ? (
      <NotFoundPage what="application" />
    ) : (
      <ErrorNotice error={app.error} onRetry={app.reload} />
    )
  }
  const a = app.data

  return (
    <>
      <p className="crumb">
        <Link to="/applications">My applications</Link>
      </p>
      <h1>Application {a.referenceNo}</h1>

      <section className="card" aria-label="Summary">
        <dl className="facts">
          <div>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={a.status} />
            </dd>
          </div>
          <div>
            <dt>Service</dt>
            <dd>{humanize(a.journeyCode)}</dd>
          </div>
          {a.submittedAt ? (
            <div>
              <dt>Submitted</dt>
              <dd>{formatDate(a.submittedAt)}</dd>
            </div>
          ) : null}
          {a.slaDueAt ? (
            <div>
              <dt>Expected by</dt>
              <dd>{formatDate(a.slaDueAt)}</dd>
            </div>
          ) : null}
        </dl>
      </section>

      <section aria-labelledby="progress-h">
        <h2 id="progress-h">Progress</h2>
        {steps.status === 'loading' ? <Loading label="Loading progress" rows={2} /> : null}
        {steps.status === 'error' ? <ErrorNotice error={steps.error} onRetry={steps.reload} /> : null}
        {steps.status === 'success' && steps.data.length === 0 ? <p className="muted">No steps have started yet.</p> : null}
        {steps.status === 'success' && steps.data.length > 0 ? (
          <ol className="timeline">
            {steps.data.map((s, i) => (
              <li key={`${s.stepCode}-${i}`}>
                <div className="row-main">
                  <strong>{humanize(s.stepCode)}</strong>
                  {s.departmentCode ? <span className="muted">{humanize(s.departmentCode)}</span> : null}
                </div>
                <StatusBadge status={s.status} />
              </li>
            ))}
          </ol>
        ) : null}
      </section>

      <section aria-labelledby="records-h">
        <h2 id="records-h">Records received</h2>
        {records.status === 'loading' ? <Loading label="Loading records" rows={2} /> : null}
        {records.status === 'error' ? <ErrorNotice error={records.error} onRetry={records.reload} /> : null}
        {records.status === 'success' && records.data.length === 0 ? (
          <p className="muted">No records have been received yet.</p>
        ) : null}
        {records.status === 'success' && records.data.length > 0 ? (
          <ul className="cards">
            {records.data.map((r, i) => (
              <li className="card record" key={i}>
                <RecordView record={r} />
              </li>
            ))}
          </ul>
        ) : null}
      </section>
    </>
  )
}
