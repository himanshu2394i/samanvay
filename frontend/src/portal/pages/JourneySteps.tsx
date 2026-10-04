import { useRef, useState, type FormEvent } from 'react'
import { useAsync } from '../../ui/useAsync'
import { PortalError, errorText, type Journey } from '../api'
import { usePortal } from '../context'
import { humanize } from '../format'
import { ErrorNotice, Loading, Notice } from '../ui'

/** Step 2: show the wording exactly as the server gave it, then confirm with a one-time code. */
export function ConsentForm({ code, onGranted }: { code: string; onGranted: () => void }) {
  const { api } = usePortal()
  const preview = useAsync(() => api.consent(code), `consent:${code}`)
  const [otp, setOtp] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const expired = error instanceof PortalError && error.status === 410

  async function confirm(e: FormEvent) {
    e.preventDefault()
    if (preview.status !== 'success') return
    setBusy(true)
    setError(null)
    try {
      await api.confirmConsent(code, preview.data.requestId, otp.trim())
      onGranted()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  function reviewAgain() {
    setError(null)
    setOtp('')
    preview.reload()
  }

  if (preview.status === 'loading') return <Loading label="Loading the consent wording" />
  if (preview.status === 'error') return <ErrorNotice error={preview.error} onRetry={preview.reload} />

  const c = preview.data
  return (
    <form onSubmit={(e) => void confirm(e)} noValidate>
      <div className="wording">
        <p className="wording-text">{c.purposeText}</p>
        <dl>
          {c.providers.length > 0 ? (
            <>
              <dt>Records will be requested from</dt>
              <dd>{c.providers.map((p) => p.name).join(', ')}</dd>
            </>
          ) : null}
          {c.categories.length > 0 ? (
            <>
              <dt>Documents</dt>
              <dd>{c.categories.map(humanize).join(', ')}</dd>
            </>
          ) : null}
          <dt>This consent lasts</dt>
          <dd>{c.validityDays} days</dd>
        </dl>
        <p className="muted">You can withdraw this consent at any time.</p>
      </div>

      {expired ? (
        <Notice
          tone="bad"
          action={
            <button type="button" className="btn secondary" onClick={reviewAgain}>
              Review again
            </button>
          }
        >
          This request has expired. Review the wording again to continue.
        </Notice>
      ) : (
        <>
          <div className="field">
            <label htmlFor="consent-otp">One-time code</label>
            <input
              id="consent-otp"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={otp}
              onChange={(e) => setOtp(e.target.value)}
            />
          </div>
          {error ? <Notice tone="bad">{errorText(error)}</Notice> : null}
          <button type="submit" className="btn" disabled={busy || !otp.trim()}>
            {busy ? 'Confirming' : 'Confirm consent'}
          </button>
        </>
      )}
    </form>
  )
}

/** Step 3: the form comes from the journey definition. Required fields are checked here. */
export function ApplyForm({
  journey,
  ready,
  onSubmit,
}: {
  journey: Journey
  ready: boolean
  onSubmit: (submission: Record<string, string>) => Promise<void>
}) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [missing, setMissing] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const formRef = useRef<HTMLFormElement>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const empty = journey.form.filter((f) => f.required && !(values[f.name] ?? '').trim()).map((f) => f.name)
    setMissing(empty)
    if (empty.length > 0) {
      setError(null)
      formRef.current?.querySelector<HTMLElement>(`[name="${empty[0]}"]`)?.focus()
      return
    }
    setBusy(true)
    setError(null)
    try {
      const submission: Record<string, string> = {}
      for (const f of journey.form) submission[f.name] = (values[f.name] ?? '').trim()
      await onSubmit(submission)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form ref={formRef} onSubmit={(e) => void submit(e)} noValidate>
      <p className="muted">Every field is required unless it says Optional.</p>
      <fieldset disabled={!ready || busy}>
        <legend className="sr-only">Application details</legend>
        {journey.form.map((f) => {
          const id = `f-${f.name}`
          const bad = missing.includes(f.name)
          const describedBy = [bad ? `${id}-err` : '', !f.required ? `${id}-hint` : ''].filter(Boolean).join(' ') || undefined
          const common = {
            id,
            name: f.name,
            required: f.required,
            'aria-invalid': bad || undefined,
            'aria-describedby': describedBy,
            value: values[f.name] ?? '',
          }
          return (
            <div className="field" key={f.name}>
              <label htmlFor={id}>{f.label}</label>
              {f.type === 'select' ? (
                <select {...common} onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}>
                  <option value="">Choose one</option>
                  {(f.options ?? []).map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              ) : (
                <input type="text" {...common} onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))} />
              )}
              {!f.required ? (
                <span className="hint" id={`${id}-hint`}>
                  Optional
                </span>
              ) : null}
              {bad ? (
                <span className="field-error" id={`${id}-err`}>
                  This field is required.
                </span>
              ) : null}
            </div>
          )
        })}
      </fieldset>
      {missing.length > 0 ? <Notice tone="bad">Please fill in the fields marked as required.</Notice> : null}
      {error ? <Notice tone="bad">{errorText(error)}</Notice> : null}
      <button type="submit" className="btn" disabled={!ready || busy}>
        {busy ? 'Submitting' : 'Submit'}
      </button>
    </form>
  )
}
