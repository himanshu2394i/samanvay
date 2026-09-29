import { useState, type FormEvent } from 'react'
import { useStaffApi } from '../../../api/apiContext'
import type { AuditVerification } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { formatDateTime } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { Tile } from '../../../ui/Tile'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'

const OUTCOME_TONE: Record<string, 'ok' | 'warn' | 'bad'> = { ALLOWED: 'ok', DENIED: 'warn', ERROR: 'bad' }

/** Read-only audit ledger: chain head, latest signed checkpoint, verification, entries. */
export function AuditPage() {
  const api = useStaffApi()
  const [action, setAction] = useState('')
  const [applied, setApplied] = useState('')
  const head = useAsync(async () => ({ head: await api.auditHead(), checkpoint: (await api.auditCheckpoint()) ?? null }), 'audit-head')
  const entries = useAsync(() => api.auditEntries({ action: applied || undefined }), `entries:${applied}`)
  const verify = useAction()
  const [result, setResult] = useState<AuditVerification | null>(null)

  async function runVerify() {
    setResult(null)
    await verify.run('verify', async () => setResult(await api.auditVerify()), 'Verification finished.')
  }

  function filter(e: FormEvent) {
    e.preventDefault()
    setApplied(action.trim())
  }

  return (
    <section aria-labelledby="audit-h">
      <h1 id="audit-h">Audit ledger</h1>
      <p className="lede">Every access and decision is chained; verification recomputes the chain from the start.</p>

      {head.status === 'loading' ? <Loading label="Loading ledger state" /> : null}
      {head.status === 'error' ? <ErrorNotice error={head.error} onRetry={head.reload} /> : null}
      {head.status === 'success' ? (
        <div className="tiles">
          <Tile label="Head entry" value={head.data.head.seq} />
          <Tile
            label="Latest checkpoint"
            value={head.data.checkpoint ? `#${head.data.checkpoint.seq}` : 'none yet'}
            sub={head.data.checkpoint ? `up to entry ${head.data.checkpoint.uptoEntrySeq}, signed ${formatDateTime(head.data.checkpoint.signedAt)}` : undefined}
          />
        </div>
      ) : null}

      <div className="actions">
        <button type="button" className="btn primary" onClick={() => void runVerify()} disabled={verify.busy !== null}>
          {verify.busy ? 'Verifying…' : 'Verify chain'}
        </button>
      </div>
      {verify.error ? <ErrorNotice error={verify.error} /> : null}
      {result ? (
        result.valid ? (
          <div className="notice ok" role="status">
            <p>
              <strong>Chain valid.</strong> Entries {result.fromSeq} to {result.toSeq} verified.
            </p>
          </div>
        ) : (
          <div className="notice bad" role="alert">
            <p>
              <strong>Chain broken</strong> at entry {result.failedAtSeq ?? 'unknown'}
              {result.reason ? `: ${result.reason}` : '.'}
            </p>
          </div>
        )
      ) : null}

      <h2>Entries</h2>
      <form className="inline-form" onSubmit={filter}>
        <Field label="Filter by action" value={action} onChange={(e) => setAction(e.target.value)} placeholder="e.g. GRANT_DENIED" />
        <button type="submit" className="btn">
          Filter
        </button>
      </form>
      {entries.status === 'loading' ? <Loading label="Loading entries" /> : null}
      {entries.status === 'error' ? <ErrorNotice error={entries.error} onRetry={entries.reload} /> : null}
      {entries.status === 'success' ? (
        entries.data.length === 0 ? (
          <p>No entries{applied ? ` for action ${applied}` : ''}.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Audit entries</caption>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">When</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Action</th>
                  <th scope="col">Subject</th>
                  <th scope="col">Department</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Reason</th>
                </tr>
              </thead>
              <tbody>
                {entries.data.map((e) => (
                  <tr key={e.seq}>
                    <td>{e.seq}</td>
                    <td>{formatDateTime(e.ts)}</td>
                    <td className="mono">{e.actorId}</td>
                    <td className="mono">{e.action}</td>
                    <td className="mono">{e.subjectId ?? 'n/a'}</td>
                    <td>{e.departmentId ?? 'n/a'}</td>
                    <td>
                      <Badge tone={OUTCOME_TONE[e.outcome] ?? 'neutral'}>{e.outcome}</Badge>
                    </td>
                    <td>{e.reason ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : null}
    </section>
  )
}
