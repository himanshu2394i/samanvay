import { useRef, useState } from 'react'
import { useStaffApi } from '../../../api/apiContext'
import type { BankReview } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import { bankReviewTone } from '../lib/status'

/** The API's ceiling (PassbookUpload.MAX_BYTES). The server checks type and size again by content. */
export const PASSBOOK_MAX_BYTES = 256 * 1024

export function BankReviewsPage() {
  const api = useStaffApi()
  const reviews = useAsync(() => api.listBankReviews(), 'bank-reviews')
  const action = useAction()

  return (
    <section aria-labelledby="bank-h">
      <h1 id="bank-h">Bank-account reviews</h1>
      <p className="lede">
        Checks where the bank's answer needs a person. You see the masked account and the reason, never the account holder's name.
      </p>
      <div className="actions">
        <button type="button" className="btn" onClick={reviews.reload}>
          Refresh
        </button>
      </div>

      {action.done ? (
        <div className="notice ok" role="status">
          <p>{action.done}</p>
        </div>
      ) : null}
      {action.error ? <ErrorNotice error={action.error} /> : null}

      {reviews.status === 'loading' ? <Loading label="Loading reviews" /> : null}
      {reviews.status === 'error' ? <ErrorNotice error={reviews.error} onRetry={reviews.reload} /> : null}
      {reviews.status === 'success' ? (
        reviews.data.length === 0 ? (
          <p>No reviews waiting. Nothing to do.</p>
        ) : (
          <ul className="plain stack">
            {reviews.data.map((r) => (
              <li key={r.id}>
                <ReviewCard review={r} action={action} onDone={reviews.reload} />
              </li>
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}

function ReviewCard({
  review: r,
  action,
  onDone,
}: {
  review: BankReview
  action: ReturnType<typeof useAction>
  onDone: () => void
}) {
  const api = useStaffApi()
  const fileRef = useRef<HTMLInputElement>(null)
  const [reason, setReason] = useState('')
  const [local, setLocal] = useState<string | null>(null)
  const disabled = action.busy !== null

  async function act(key: string, fn: () => Promise<unknown>, done: string) {
    setLocal(null)
    if (await action.run(`${r.id}:${key}`, fn, done)) {
      setReason('')
      onDone()
    }
  }

  function upload() {
    const file = fileRef.current?.files?.[0]
    if (!file) return setLocal('Choose a passbook or cancelled-cheque file first.')
    if (file.size > PASSBOOK_MAX_BYTES) return setLocal('That file is larger than 256 KB. Use a smaller PDF, JPEG or PNG.')
    void act('upload', () => api.uploadPassbook(r.id, file), `Passbook uploaded for application ${r.applicationId}.`)
  }

  function reject() {
    if (!reason.trim()) return setLocal('A reason is required to reject.')
    void act('reject', () => api.rejectBankReview(r.id, reason.trim()), `Rejected the review for application ${r.applicationId}.`)
  }

  const busyHere = (k: string) => action.busy === `${r.id}:${k}`

  return (
    <article className="card" aria-label={`Bank review for application ${r.applicationId}`}>
      <h2>Application {r.applicationId}</h2>
      <dl className="facts">
        <div className="fact">
          <dt>Account</dt>
          <dd className="mono">{r.accountMasked}</dd>
        </div>
        <div className="fact">
          <dt>Result</dt>
          <dd>{r.reasonText}</dd>
        </div>
        <div className="fact">
          <dt>Matcher</dt>
          <dd>{r.matcherVersion ?? 'n/a'}</dd>
        </div>
        <div className="fact">
          <dt>Status</dt>
          <dd>
            <Badge tone={bankReviewTone(r.status)}>{humanize(r.status)}</Badge>
            {r.hasDocument ? ' Passbook received' : ' No passbook yet'}
          </dd>
        </div>
        <div className="fact">
          <dt>Raised</dt>
          <dd>{formatDateTime(r.createdAt)}</dd>
        </div>
      </dl>

      <div className="field">
        <label htmlFor={`file-${r.id}`}>Passbook or cancelled cheque (PDF, JPEG or PNG, up to 256 KB)</label>
        <input id={`file-${r.id}`} ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" />
      </div>
      <div className="field">
        <label htmlFor={`reason-${r.id}`}>Reason (needed to reject, optional to approve)</label>
        <input id={`reason-${r.id}`} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
      </div>
      {local ? (
        <p className="field-error" role="alert">
          {local}
        </p>
      ) : null}
      <div className="actions">
        <button type="button" className="btn" onClick={upload} disabled={disabled}>
          {busyHere('upload') ? 'Uploading…' : 'Upload passbook'}
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => void act('request', () => api.requestDocument(r.id), `Asked for a document on application ${r.applicationId}.`)}
          disabled={disabled}
        >
          {busyHere('request') ? 'Asking…' : 'Ask for a document'}
        </button>
        <button
          type="button"
          className="btn primary"
          onClick={() => void act('approve', () => api.approveBankReview(r.id, reason.trim() || undefined), `Approved the review for application ${r.applicationId}.`)}
          disabled={disabled}
        >
          {busyHere('approve') ? 'Approving…' : 'Approve'}
        </button>
        <button type="button" className="btn danger" onClick={reject} disabled={disabled}>
          {busyHere('reject') ? 'Rejecting…' : 'Reject'}
        </button>
      </div>
    </article>
  )
}
