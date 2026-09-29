import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { useCitizen } from '../CitizenContext'
import { formatDate, humanize } from '../lib/format'

/** Badge colour for each consent state: active is reassuring, withdrawn is a clear stop, the rest are neutral. */
function toneFor(status: string): 'ok' | 'bad' | 'neutral' {
  if (status === 'ACTIVE') return 'ok'
  if (status === 'REVOKED') return 'bad'
  return 'neutral'
}

export function ConsentsPage() {
  const api = useCitizenApi()
  const { citizenId } = useCitizen()
  const consents = useAsync(() => api.listConsents(citizenId ?? ''), citizenId ?? '')
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [justWithdrawn, setJustWithdrawn] = useState<string | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)

  // After a withdrawal lands, move focus to the confirmation so nothing is lost when the
  // action button it replaced disappears.
  useEffect(() => {
    if (justWithdrawn && consents.status === 'success' && panelRef.current) {
      panelRef.current.focus()
    }
  }, [justWithdrawn, consents.status, consents.data])

  async function withdraw(id: string) {
    setBusy(true)
    setError(null)
    try {
      await api.revokeConsent(id)
      setConfirming(null)
      setJustWithdrawn(id)
      consents.reload()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="consents-h">
      <h1 id="consents-h">My consents</h1>
      <p className="lede">Every consent you have given. Withdrawing one stops any further fetching under it.</p>
      {consents.status === 'loading' ? <Loading label="Loading your consents" /> : null}
      {consents.status === 'error' ? <ErrorNotice error={consents.error} onRetry={consents.reload} /> : null}
      {error ? <ErrorNotice error={error} /> : null}
      {consents.status === 'success' ? (
        consents.data.length === 0 ? (
          <p>
            You have not given any consent yet. It is asked for when you <Link to="/services">apply for a service</Link>.
          </p>
        ) : (
          <ul className="stack">
            {consents.data.map((c) => (
              <li key={c.id} className="card">
                <h2>
                  {humanize(c.purposeCode)} <Badge tone={toneFor(c.status)}>{c.statusLabel}</Badge>
                </h2>
                <dl className="facts">
                  <dt>Asked by</dt>
                  <dd>{humanize(c.requesterId)}</dd>
                  <dt>Records</dt>
                  <dd>{c.categories.map(humanize).join(', ') || 'n/a'}</dd>
                  <dt>Valid</dt>
                  <dd>
                    {formatDate(c.validFrom)} to {formatDate(c.validUntil)}
                  </dd>
                </dl>
                {c.status === 'REVOKED' ? (
                  <div
                    className="notice bad"
                    role="status"
                    tabIndex={-1}
                    ref={justWithdrawn === c.id ? panelRef : undefined}
                  >
                    <p>
                      This consent is withdrawn. Any new request for these records will now be refused. Samanvay never
                      stored the records themselves, so there is nothing to delete.
                    </p>
                  </div>
                ) : null}
                {c.status === 'ACTIVE' ? (
                  confirming === c.id ? (
                    <div className="actions">
                      <button type="button" className="btn danger" disabled={busy} onClick={() => void withdraw(c.id)}>
                        {busy ? 'Withdrawing…' : 'Yes, withdraw this consent'}
                      </button>
                      <button type="button" className="btn" disabled={busy} onClick={() => setConfirming(null)}>
                        Keep it
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="btn"
                      aria-label={`Withdraw consent for ${humanize(c.purposeCode)}`}
                      onClick={() => setConfirming(c.id)}
                    >
                      Withdraw consent
                    </button>
                  )
                ) : null}
              </li>
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}
