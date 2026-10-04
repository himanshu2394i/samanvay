import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAsync } from '../../ui/useAsync'
import { usePortal } from '../context'
import { formatDate, humanize } from '../format'
import { ErrorNotice, Loading, Notice, StatusBadge } from '../ui'

const POLL_MS = 1000
const POLL_MAX = 10

export function ApplicationsPage() {
  const { api } = usePortal()
  const location = useLocation()
  // Set by the journey page right after Submit: how many applications there were before.
  const expectAfter = (location.state as { submittedAfter?: number } | null)?.submittedAfter ?? null
  const list = useAsync(() => api.applications(), 'applications')
  const { reload } = list
  const [polls, setPolls] = useState(0)

  const count = list.status === 'success' ? list.data.length : 0
  const waiting = expectAfter !== null && list.status !== 'error' && count <= expectAfter && polls < POLL_MAX

  useEffect(() => {
    if (!waiting) return
    const t = setTimeout(() => {
      setPolls((p) => p + 1)
      reload()
    }, POLL_MS)
    return () => clearTimeout(t)
  }, [waiting, polls, reload])

  return (
    <>
      <h1>My applications</h1>

      {expectAfter !== null && count > expectAfter ? (
        <Notice tone="ok">Your application has been submitted. You can follow it below.</Notice>
      ) : null}
      {waiting ? <Notice tone="info">Your application has been submitted. It can take a few seconds to appear here.</Notice> : null}
      {expectAfter !== null && !waiting && count <= expectAfter && list.status === 'success' ? (
        <Notice
          tone="info"
          action={
            <button type="button" className="btn secondary" onClick={list.reload}>
              Refresh
            </button>
          }
        >
          Your application has been submitted but is taking longer than usual to show up. Refresh in a moment.
        </Notice>
      ) : null}

      {list.status === 'loading' ? <Loading label="Loading your applications" /> : null}
      {list.status === 'error' ? <ErrorNotice error={list.error} onRetry={list.reload} /> : null}
      {list.status === 'success' && list.data.length === 0 && !waiting ? (
        <div className="empty">
          <p>You have not applied for anything yet.</p>
          <Link className="btn" to="/">
            See services
          </Link>
        </div>
      ) : null}
      {list.status === 'success' && list.data.length > 0 ? (
        <ul className="cards">
          {list.data.map((a) => (
            <li className="card application" key={a.referenceNo}>
              <div className="app-head">
                <Link to={`/applications/${encodeURIComponent(a.referenceNo)}`} className="ref">
                  {a.referenceNo}
                </Link>
                <StatusBadge status={a.status} />
              </div>
              <p className="muted">
                {humanize(a.journeyCode)}
                {a.slaDueAt ? `. Expected by ${formatDate(a.slaDueAt)}` : ''}
              </p>
            </li>
          ))}
        </ul>
      ) : null}
    </>
  )
}
