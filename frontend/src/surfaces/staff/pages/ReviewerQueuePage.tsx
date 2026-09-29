import { useState } from 'react'
import { useStaffApi } from '../../../api/apiContext'
import type { IdentityCandidate } from '../../../api/staffTypes'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { shortId } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'

/** REVIEWER only: candidate account links a person must confirm or reject (never auto-linked). */
export function ReviewerQueuePage() {
  const api = useStaffApi()
  const queue = useAsync(async () => {
    const page = await api.reviewQueue(50)
    return Array.isArray(page) ? page : (page.content ?? [])
  }, 'review-queue')
  const action = useAction()
  const [notes, setNotes] = useState<Record<string, string>>({})

  async function decide(c: IdentityCandidate, verb: 'confirm' | 'reject') {
    const note = notes[c.id]?.trim() || undefined
    const ok = await action.run(
      `${c.id}:${verb}`,
      () => (verb === 'confirm' ? api.confirmCandidate(c.id, note) : api.rejectCandidate(c.id, note)),
      verb === 'confirm' ? `Confirmed the link for citizen ${shortId(c.citizenId)}.` : `Rejected the candidate for citizen ${shortId(c.citizenId)}.`,
    )
    if (ok) queue.reload()
  }

  return (
    <section aria-labelledby="rq-h">
      <h1 id="rq-h">Identity review</h1>
      <p className="lede">Possible matches between a citizen and a department record. Nothing is linked until a reviewer confirms it.</p>
      <div className="actions">
        <button type="button" className="btn" onClick={queue.reload}>
          Refresh
        </button>
      </div>
      {action.done ? (
        <div className="notice ok" role="status">
          <p>{action.done}</p>
        </div>
      ) : null}
      {action.error ? <ErrorNotice error={action.error} /> : null}
      {queue.status === 'loading' ? <Loading label="Loading the review queue" /> : null}
      {queue.status === 'error' ? <ErrorNotice error={queue.error} onRetry={queue.reload} /> : null}
      {queue.status === 'success' ? (
        queue.data.length === 0 ? (
          <p>The review queue is empty.</p>
        ) : (
          <ul className="plain stack">
            {queue.data.map((c) => (
              <li key={c.id} className="card">
                <h2>
                  Citizen <span className="mono">{shortId(c.citizenId)}</span> and {c.departmentCode}
                </h2>
                <p>
                  Match score {(c.score * 100).toFixed(0)}%. Status {c.status.toLowerCase()}.
                </p>
                <div className="field">
                  <label htmlFor={`note-${c.id}`}>Note (optional)</label>
                  <input id={`note-${c.id}`} value={notes[c.id] ?? ''} onChange={(e) => setNotes({ ...notes, [c.id]: e.target.value })} maxLength={500} />
                </div>
                <div className="actions">
                  <button type="button" className="btn primary" disabled={action.busy !== null} onClick={() => void decide(c, 'confirm')}>
                    {action.busy === `${c.id}:confirm` ? 'Confirming…' : 'Confirm link'}
                  </button>
                  <button type="button" className="btn danger" disabled={action.busy !== null} onClick={() => void decide(c, 'reject')}>
                    {action.busy === `${c.id}:reject` ? 'Rejecting…' : 'Reject'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}
