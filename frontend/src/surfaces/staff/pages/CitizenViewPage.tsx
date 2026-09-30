import { Link, useParams } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { AuditRecord } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { Tile } from '../../../ui/Tile'
import { useAsync } from '../../../ui/useAsync'
import { appStatus, isOpenApplication, SLA_BADGE, slaState, type Tone } from '../lib/status'

/** Colour an audit outcome — never on colour alone, the label always says what happened. */
function outcomeTone(outcome: string): Tone {
  const o = outcome.toUpperCase()
  if (/(DENIED|FAIL|REJECT|ERROR|BLOCK)/.test(o)) return 'bad'
  if (/(GRANT|OK|SUCCESS|APPROV|ALLOW|COMPLETE)/.test(o)) return 'ok'
  return 'neutral'
}

/**
 * The officer's 360° view of one citizen: every application they have filed across services,
 * and their consent / data-access trail from the audit ledger. Composed from endpoints the
 * officer already has (listApplications, auditEntries) — Samanvay stores none of the underlying
 * records, and the audit trail is best-effort so its failure never hides the applications.
 */
export function CitizenViewPage() {
  const { citizenId = '' } = useParams()
  const api = useStaffApi()

  const data = useAsync(async () => {
    const [apps, trail] = await Promise.all([
      api.listApplications(200),
      api.auditEntries({ size: 200 }).then(
        (list) => list.filter((e) => e.subjectId === citizenId),
        () => null as AuditRecord[] | null,
      ),
    ])
    return { loadedAt: Date.now(), apps: apps.filter((a) => a.citizenId === citizenId), trail }
  }, citizenId)

  if (data.status === 'loading') return <Loading label="Loading the citizen file" />
  if (data.status === 'error') {
    return (
      <section aria-labelledby="cz-h">
        <h1 id="cz-h">Citizen file</h1>
        <ErrorNotice error={data.error} onRetry={data.reload} />
        <p>
          <Link to="/staff/officer/applications">All applications</Link>
        </p>
      </section>
    )
  }

  const { apps, trail, loadedAt } = data.data
  const open = apps.filter((a) => isOpenApplication(a.status)).length
  const approved = apps.filter((a) => a.status === 'APPROVED').length
  const rejected = apps.filter((a) => a.status === 'REJECTED').length
  const departments = trail ? [...new Set(trail.map((e) => e.departmentId).filter((d): d is string => !!d))] : []

  return (
    <section aria-labelledby="cz-h">
      <p>
        <Link to="/staff/officer/applications">All applications</Link>
      </p>
      <h1 id="cz-h">Citizen file</h1>
      <p className="lede">
        Everything this citizen has filed and consented to, in one place. Samanvay stores none of the
        underlying records — this is assembled from the tracking and audit trail on demand.
      </p>
      <dl className="facts">
        <div className="fact">
          <dt>Citizen</dt>
          <dd className="mono">{citizenId}</dd>
        </div>
      </dl>

      <div className="tiles">
        <Tile label="Applications" value={apps.length} />
        <Tile label="Open" value={open} tone={open > 0 ? 'warn' : 'ok'} />
        <Tile label="Approved" value={approved} tone={approved > 0 ? 'ok' : undefined} />
        <Tile label="Rejected" value={rejected} tone={rejected > 0 ? 'bad' : undefined} />
      </div>

      <h2>Applications</h2>
      {apps.length === 0 ? (
        <p>No applications on file for this citizen.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">This citizen's applications</caption>
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col">Service</th>
                <th scope="col">Status</th>
                <th scope="col">SLA due</th>
              </tr>
            </thead>
            <tbody>
              {apps.map((a) => {
                const st = appStatus(a.status)
                const sla = slaState(a.slaDueAt, isOpenApplication(a.status), loadedAt)
                return (
                  <tr key={a.referenceNo}>
                    <td>
                      <Link to={`/staff/officer/applications/${encodeURIComponent(a.referenceNo)}`}>{a.referenceNo}</Link>
                    </td>
                    <td className="mono">{a.journeyCode}</td>
                    <td>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </td>
                    <td>
                      {formatDateTime(a.slaDueAt) || 'n/a'}{' '}
                      {sla !== 'none' ? <Badge tone={SLA_BADGE[sla].tone}>{SLA_BADGE[sla].label}</Badge> : null}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <h2>Consent &amp; data access</h2>
      <p className="hint">
        From the tamper-evident audit ledger: what this citizen agreed to, and every access made on
        their behalf.
      </p>
      {departments.length > 0 ? (
        <ul className="plain chips" aria-label="Departments involved">
          {departments.map((d) => (
            <li key={d}>
              <Badge tone="neutral">{humanize(d)}</Badge>
            </li>
          ))}
        </ul>
      ) : null}
      {trail === null ? (
        <p className="notice warn" role="status">
          The audit trail could not be loaded. The applications above are unaffected.
        </p>
      ) : trail.length === 0 ? (
        <p>No consent or data-access entries for this citizen yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Consent and data-access trail</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Event</th>
                <th scope="col">Department</th>
                <th scope="col">Outcome</th>
                <th scope="col">Reason</th>
              </tr>
            </thead>
            <tbody>
              {trail.map((e) => (
                <tr key={e.seq}>
                  <td>{formatDateTime(e.ts)}</td>
                  <td>{humanize(e.action)}</td>
                  <td className="mono">{e.departmentId ?? 'n/a'}</td>
                  <td>
                    <Badge tone={outcomeTone(e.outcome)}>{humanize(e.outcome)}</Badge>
                  </td>
                  <td>{e.reason ? humanize(e.reason) : 'n/a'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
