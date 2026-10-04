import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import { ApiError } from '../../../api/client'
import type { JourneyException } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { StatusStepper } from '../../../ui/StatusStepper'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import { appStatus, isOpenApplication, SLA_BADGE, slaState, stepTone } from '../lib/status'

export function ApplicationReviewPage() {
  const { ref = '' } = useParams()
  const api = useStaffApi()
  const action = useAction()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  // Approve and reject cannot be undone, so each takes a second click ("Confirm ...") first.
  const [confirming, setConfirming] = useState<'approve' | 'reject' | null>(null)

  const data = useAsync(async () => {
    const app = await api.getApplication(ref)
    const [steps, records, exceptions] = await Promise.all([
      api.getApplicationSteps(ref),
      // The records are a live preview from the departments; their failure must not hide the case.
      api.getIssuedRecords(ref).then(
        (r) => ({ ok: true as const, records: r }),
        (e: unknown) => ({ ok: false as const, error: e }),
      ),
      // Like the records, the exception queue is a side panel: say so when it cannot be read.
      api.listExceptions().then(
        (list) => ({ ok: true as const, list }),
        () => ({ ok: false as const, list: [] as JourneyException[] }),
      ),
    ])
    return {
      loadedAt: Date.now(),
      app,
      steps,
      records,
      exceptionsOk: exceptions.ok,
      exceptions: exceptions.list.filter((x) => app.instanceId !== null && x.instanceId === app.instanceId),
    }
  }, ref)

  if (data.status === 'loading') return <Loading label="Loading the application" />
  if (data.status === 'error') {
    const missing = data.error instanceof ApiError && data.error.status === 404
    return (
      <section aria-labelledby="app-h">
        <h1 id="app-h">Application {ref}</h1>
        {missing ? <p>No application with that number was found.</p> : <ErrorNotice error={data.error} onRetry={data.reload} />}
        <p>
          <Link to="/staff/officer/applications">Back to applications</Link>
        </p>
      </section>
    )
  }

  const { app, steps, records, exceptions, exceptionsOk, loadedAt } = data.data
  // While the case reloads after a write, no write may start: the buttons would act on stale state.
  const locked = action.busy !== null || data.refreshing
  const st = appStatus(app.status)
  const open = isOpenApplication(app.status)
  const sla = slaState(app.slaDueAt, open, loadedAt)

  async function retry(instanceId: string) {
    if (await action.run(`retry:${instanceId}`, () => api.retryInstance(instanceId), 'Retry requested. Refresh in a moment to see whether the step cleared.')) data.reload()
  }

  async function approve(instanceId: string) {
    setConfirming(null)
    if (await action.run(`approve:${instanceId}`, () => api.approveApplication(instanceId), 'Application approved.')) data.reload()
  }

  function askReject() {
    if (!reason.trim()) {
      setReasonError('A reason is required to reject.')
      return
    }
    setReasonError(null)
    setConfirming('reject')
  }

  async function reject(instanceId: string) {
    setConfirming(null)
    if (await action.run(`reject:${instanceId}`, () => api.rejectApplication(instanceId, reason.trim()), 'Application rejected.')) {
      setReason('')
      data.reload()
    }
  }

  return (
    <section aria-labelledby="app-h">
      <p>
        <Link to="/staff/officer/applications">All applications</Link>
      </p>
      <h1 id="app-h">Application {app.referenceNo}</h1>

      <StatusStepper status={app.status} />

      <dl className="facts">
        <div className="fact">
          <dt>Status</dt>
          <dd>
            <Badge tone={st.tone}>{st.label}</Badge>
          </dd>
        </div>
        <div className="fact">
          <dt>Service</dt>
          <dd className="mono">{app.journeyCode}</dd>
        </div>
        <div className="fact">
          <dt>Submitted</dt>
          <dd>{formatDateTime(app.submittedAt) || 'n/a'}</dd>
        </div>
        <div className="fact">
          <dt>SLA due</dt>
          <dd>
            {formatDateTime(app.slaDueAt) || 'n/a'} {sla !== 'none' ? <Badge tone={SLA_BADGE[sla].tone}>{SLA_BADGE[sla].label}</Badge> : null}
          </dd>
        </div>
        <div className="fact">
          <dt>Citizen</dt>
          <dd>
            <span className="mono">{app.citizenId}</span>{' '}
            <Link to={`/staff/officer/citizens/${encodeURIComponent(app.citizenId)}`}>Citizen file</Link>
          </dd>
        </div>
      </dl>

      {action.done ? (
        <div className="notice ok" role="status">
          <p>{action.done}</p>
        </div>
      ) : null}
      {action.error ? <ErrorNotice error={action.error} /> : null}

      {!exceptionsOk ? (
        <p className="notice warn" role="status">
          The exception queue could not be checked, so open exceptions for this application may not be listed.
        </p>
      ) : null}
      {exceptions.length > 0 ? (
        <div className="notice warn" aria-label="Open exceptions">
          <h2>Needs an officer</h2>
          <ul className="plain">
            {exceptions.map((x) => (
              <li key={x.id}>
                <span className="mono">{x.stepCode}</span>: {x.reason} ({formatDateTime(x.createdAt)}){' '}
                <button type="button" className="btn primary" onClick={() => void retry(x.instanceId)} disabled={locked}>
                  {action.busy === `retry:${x.instanceId}` ? 'Retrying…' : 'Retry'}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <h2>Department checks</h2>
      {steps.length === 0 ? (
        <p>No department checks have started yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Department checks</caption>
            <thead>
              <tr>
                <th scope="col">Check</th>
                <th scope="col">Department</th>
                <th scope="col">Status</th>
                <th scope="col">Source</th>
                <th scope="col">Completed</th>
                <th scope="col">Outcome</th>
              </tr>
            </thead>
            <tbody>
              {steps.map((s) => {
                const t = stepTone(s.status)
                return (
                  <tr key={`${s.stepCode}-${s.departmentCode}`}>
                    <td>{humanize(s.stepCode)}</td>
                    <td>{s.departmentCode}</td>
                    <td>
                      <Badge tone={t.tone}>{t.label}</Badge>
                    </td>
                    <td>{s.source ?? 'n/a'}</td>
                    <td>{formatDateTime(s.completedAt) || 'n/a'}</td>
                    <td>{s.outcome ?? 'n/a'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <h2>Records received</h2>
      <p className="hint">A live preview from each department. Samanvay does not store these records.</p>
      {!records.ok ? (
        <ErrorNotice error={records.error} onRetry={data.reload} />
      ) : records.records.length === 0 ? (
        <p>No records to show yet.</p>
      ) : (
        <ul className="plain stack">
          {records.records.map((r) => (
            <li key={`${r.stepCode}-${r.departmentCode}`} className="card">
              <h3>{r.title}</h3>
              <p className="hint">
                {r.issuer} via {r.liveSystem}: {humanize(r.fetchStatus)}
              </p>
              {r.fields.length > 0 ? (
                <dl className="facts">
                  {r.fields.map((f) => (
                    <div className="fact" key={f.label}>
                      <dt>{f.label}</dt>
                      <dd>{f.value}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      <h2>Decision</h2>
      {/* Approval is officer-only and applies only to a VERIFIED application: the endpoint answers 409
          (mapped to a plain sentence in ui/errors.ts) for any other status. The route keys off the
          `instanceId` of the application view, not the reference number. */}
      {(() => {
        const canApprove = app.status === 'VERIFIED' && app.instanceId !== null
        // Reject mirrors approve but applies to any non-terminal application (the endpoint answers 409
        // once it is APPROVED/REJECTED/CLOSED), and always needs a reason (a blank one is a 400).
        const canReject = open && app.instanceId !== null
        const rejectKey = `reject:${app.instanceId}`
        const approveKey = `approve:${app.instanceId}`
        return (
          <>
            <div className="actions">
              {confirming === 'approve' && canApprove ? (
                <>
                  <button type="button" className="btn primary" onClick={() => app.instanceId !== null && void approve(app.instanceId)} disabled={locked}>
                    Confirm approve
                  </button>
                  <button type="button" className="btn" onClick={() => setConfirming(null)} disabled={locked}>
                    Cancel
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="btn primary"
                  onClick={() => setConfirming('approve')}
                  disabled={!canApprove || locked}
                  aria-describedby={canApprove ? undefined : 'approve-why'}
                >
                  {action.busy === approveKey ? 'Approving…' : 'Approve application'}
                </button>
              )}
              {!canApprove ? (
                <p id="approve-why" className="hint">
                  Available once every department record is verified
                </p>
              ) : null}
            </div>
            {canReject ? (
              <div className="reject">
                <label htmlFor="reject-reason">Reason (required to reject)</label>
                <textarea
                  id="reject-reason"
                  value={reason}
                  onChange={(e) => {
                    setReason(e.target.value)
                    if (confirming === 'reject') setConfirming(null)
                  }}
                  rows={2}
                  aria-describedby={reasonError ? 'reject-why' : undefined}
                  aria-invalid={reasonError ? true : undefined}
                />
                {reasonError ? (
                  <p id="reject-why" className="hint bad" role="alert">
                    {reasonError}
                  </p>
                ) : null}
                {confirming === 'reject' ? (
                  <>
                    <button type="button" className="btn danger" onClick={() => app.instanceId !== null && void reject(app.instanceId)} disabled={locked}>
                      Confirm reject
                    </button>
                    <button type="button" className="btn" onClick={() => setConfirming(null)} disabled={locked}>
                      Cancel
                    </button>
                  </>
                ) : (
                  <button type="button" className="btn danger" onClick={askReject} disabled={locked}>
                    {action.busy === rejectKey ? 'Rejecting…' : 'Reject application'}
                  </button>
                )}
              </div>
            ) : null}
          </>
        )
      })()}
    </section>
  )
}
