import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { OverviewJourney } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import { publishTone, sourceState } from '../lib/status'
import { ADMIN } from '../nav'
import { useStaffSession } from '../StaffContext'

interface Row extends OverviewJourney {
  requester: string
  /** Health of each needed document's source, keyed `DEPARTMENT/CATEGORY`, read from the same overview. */
  health: Map<string, string>
}

/**
 * What a journey is waiting for, in words; null when nothing is. A need whose source is RED blocks it even on a draft
 * whose connectors all exist (`ready`), so the warning is decided before "ready to publish".
 */
function waitingFor(j: Row): string | null {
  const missing = j.needs.filter((n) => sourceState(n.working, j.health.get(`${n.department}/${n.category}`)) === 'blocked').map((n) => humanize(n.category))
  if (missing.length > 0) return `Waiting for: ${missing.join(', ')}`
  return j.ready ? null : 'Waiting for: connectors'
}

/** True when every need is working but at least one source has never been checked. */
function hasUnchecked(j: Row): boolean {
  return j.needs.some((n) => sourceState(n.working, j.health.get(`${n.department}/${n.category}`)) === 'unchecked')
}

/**
 * Every journey that onboarding produced, with whether it can be published, how its applications are going and a link to
 * its status and log (GET /api/ops/overview; OFFICER, ADMIN). Publishing is an admin call.
 */
export function JourneysPage() {
  const api = useStaffApi()
  const { can } = useStaffSession()
  const canAct = can(ADMIN)
  const overview = useAsync(() => api.getOverview(), 'overview')
  const publish = useAction()

  async function publishJourney(code: string) {
    const ok = await publish.run(code, () => api.publishJourney(code), '')
    if (ok) overview.reload()
  }

  const health = new Map<string, string>()
  if (overview.status === 'success') for (const d of overview.data.departments) for (const x of d.documents) health.set(`${d.code}/${x.category}`, x.sourceHealth)
  const rows: Row[] = overview.status === 'success' ? overview.data.departments.flatMap((d) => d.journeys.map((j) => ({ ...j, requester: d.code, health }))) : []

  return (
    <section aria-labelledby="jn-h">
      <h1 id="jn-h">Journeys</h1>
      <p className="lede">
        The services onboarded from department manifests. A draft can be published once every document it needs has a published
        connector. To add one use <Link to="/staff/admin/onboarding">Onboarding</Link>.
      </p>
      <div className="actions">
        <button type="button" className="btn" onClick={overview.reload} disabled={overview.status === 'loading' || overview.refreshing}>
          {overview.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
      {overview.status === 'loading' ? <Loading variant="table" label="Loading the journeys" rows={5} /> : null}
      {overview.status === 'error' ? <ErrorNotice error={overview.error} onRetry={overview.reload} /> : null}
      {publish.error ? <ErrorNotice error={publish.error} /> : null}
      {overview.status === 'success' && rows.length === 0 ? (
        <div className="card">
          <p>
            <strong>No journey has been onboarded yet</strong>
          </p>
          <p className="hint">
            Journeys appear here once a department&rsquo;s manifest offering them is onboarded. Go to <Link to="/staff/admin/onboarding">Onboarding</Link>.
          </p>
        </div>
      ) : null}
      {rows.length > 0 ? (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Journeys</caption>
            <thead>
              <tr>
                <th scope="col">Journey</th>
                <th scope="col">Code</th>
                <th scope="col">Requester</th>
                <th scope="col">Status</th>
                <th scope="col">Readiness</th>
                <th scope="col">Running</th>
                <th scope="col">Approved</th>
                <th scope="col">Rejected</th>
                <th scope="col">Last 7 days</th>
                <th scope="col">Open</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((j) => {
                const waiting = waitingFor(j)
                return (
                  <tr key={j.code}>
                    <td>{j.name}</td>
                    <td className="mono">{j.code}</td>
                    <td className="mono">{j.requester}</td>
                    <td>
                      <Badge tone={publishTone(j.status)}>{humanize(j.status)}</Badge>
                    </td>
                    <td>
                      {waiting ? (
                        <Badge tone="warn">{waiting}</Badge>
                      ) : j.status === 'DRAFT' ? (
                        <>
                          <Badge tone="ok">Ready to publish</Badge>{' '}
                          {hasUnchecked(j) ? <Badge tone="neutral">Not checked yet</Badge> : null}{' '}
                          {canAct ? (
                            <button type="button" className="btn" onClick={() => void publishJourney(j.code)} disabled={publish.busy !== null}>
                              {publish.busy === j.code ? 'Publishing…' : 'Publish'}
                            </button>
                          ) : null}
                        </>
                      ) : hasUnchecked(j) ? (
                        <Badge tone="neutral">Not checked yet</Badge>
                      ) : (
                        <Badge tone="ok">All sources working</Badge>
                      )}
                    </td>
                    <td>{j.counts.running}</td>
                    <td>{j.counts.completed}</td>
                    <td>{j.counts.failed}</td>
                    <td>{j.counts.last7Days}</td>
                    <td>
                      <Link to={`/staff/admin/journeys/${encodeURIComponent(j.code)}`}>Status and log</Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
