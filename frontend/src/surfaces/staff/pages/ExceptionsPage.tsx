import { useState } from 'react'
import { useStaffApi } from '../../../api/apiContext'
import type { JourneyException } from '../../../api/staffTypes'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize, shortId } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'

export function ExceptionsPage() {
  const api = useStaffApi()
  const queue = useAsync(() => api.listExceptions(), 'exceptions')
  const action = useAction()
  const [open, setOpen] = useState<string | null>(null)

  async function retry(x: JourneyException) {
    const ok = await action.run(x.id, () => api.retryInstance(x.instanceId), `Retry requested for instance ${shortId(x.instanceId)}. Refresh in a moment to see whether it cleared.`)
    if (ok) queue.reload()
  }

  return (
    <section aria-labelledby="exc-h">
      <h1 id="exc-h">Exceptions</h1>
      <p className="lede">Journeys that stopped on a step and need an officer. Retry re-runs the waiting steps once the cause is fixed.</p>
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

      {queue.status === 'loading' ? <Loading variant="table" label="Loading the exception queue" /> : null}
      {queue.status === 'error' ? <ErrorNotice error={queue.error} onRetry={queue.reload} /> : null}
      {queue.status === 'success' ? (
        queue.data.length === 0 ? (
          <p>No open exceptions. Nothing needs an officer right now.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Open exceptions</caption>
              <thead>
                <tr>
                  <th scope="col">Raised</th>
                  <th scope="col">Step</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Instance</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {queue.data.map((x) => (
                  <ExceptionRows
                    key={x.id}
                    x={x}
                    expanded={open === x.id}
                    onToggle={() => setOpen(open === x.id ? null : x.id)}
                    onRetry={() => void retry(x)}
                    retrying={action.busy === x.id}
                    disabled={action.busy !== null}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : null}
    </section>
  )
}

function ExceptionRows({
  x,
  expanded,
  onToggle,
  onRetry,
  retrying,
  disabled,
}: {
  x: JourneyException
  expanded: boolean
  onToggle: () => void
  onRetry: () => void
  retrying: boolean
  disabled: boolean
}) {
  return (
    <>
      <tr>
        <td>{formatDateTime(x.createdAt)}</td>
        <td className="mono">{x.stepCode}</td>
        <td>{x.reason}</td>
        <td className="mono" title={x.instanceId}>
          {shortId(x.instanceId)}
        </td>
        <td>
          <div className="row-actions">
            <button type="button" className="btn primary" onClick={onRetry} disabled={disabled} aria-label={`Retry ${x.stepCode} for instance ${shortId(x.instanceId)}`}>
              {retrying ? 'Retrying…' : 'Retry'}
            </button>
            <button type="button" className="btn" onClick={onToggle} aria-expanded={expanded}>
              {expanded ? 'Hide steps' : 'Show steps'}
            </button>
          </div>
        </td>
      </tr>
      {expanded ? (
        <tr>
          <td colSpan={5}>
            <InstanceOutcomes instanceId={x.instanceId} />
          </td>
        </tr>
      ) : null}
    </>
  )
}

function InstanceOutcomes({ instanceId }: { instanceId: string }) {
  const api = useStaffApi()
  const state = useAsync(() => api.getInstance(instanceId), instanceId)
  if (state.status === 'loading') return <Loading label="Loading step outcomes" />
  if (state.status === 'error') return <ErrorNotice error={state.error} onRetry={state.reload} />
  const entries = Object.entries(state.data.stepOutcomes ?? {})
  return (
    <div>
      <p>
        Instance <span className="mono">{state.data.id}</span> is <strong>{humanize(state.data.status)}</strong>.
      </p>
      {entries.length === 0 ? (
        <p>No step outcomes recorded yet.</p>
      ) : (
        <dl className="facts">
          {entries.map(([step, outcome]) => (
            <div className="fact" key={step}>
              <dt className="mono">{step}</dt>
              <dd>{outcome}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  )
}
