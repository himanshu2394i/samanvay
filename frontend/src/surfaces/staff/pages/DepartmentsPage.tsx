import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { OverviewDepartment, OverviewDocument } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import { healthTone, publishTone } from '../lib/status'
import { ADMIN } from '../nav'
import { useStaffSession } from '../StaffContext'

/** Why a document is not working, in plain words; null when it is. */
function whyNot(d: OverviewDocument): string | null {
  if (d.working) return null
  if (!d.connectorRef) return 'No connector for this document yet'
  if (d.connectorStatus !== 'PUBLISHED') return 'The connector is still a draft: it has to be mapped, tested and published'
  return 'The data source is not reachable, so fetches for this document will fail'
}

/**
 * The departments that were really onboarded (from their manifests): who they are, the key their manifest is pinned to,
 * where they log in, their data sources, the documents they serve and the journeys they run (GET /api/ops/overview;
 * OFFICER, ADMIN). Checking a source and running a trial are admin-only calls.
 */
export function DepartmentsPage() {
  const api = useStaffApi()
  const overview = useAsync(() => api.getOverview(), 'overview')

  return (
    <section aria-labelledby="dept-h">
      <h1 id="dept-h">Departments</h1>
      <p className="lede">
        The departments onboarded from their manifests, what each one provides and whether it is working. To add one use{' '}
        <Link to="/staff/admin/onboarding">Onboarding</Link>.
      </p>
      <div className="actions">
        <button type="button" className="btn" onClick={overview.reload} disabled={overview.status === 'loading' || overview.refreshing}>
          {overview.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
      {overview.status === 'loading' ? <Loading variant="table" label="Loading the departments" rows={5} /> : null}
      {overview.status === 'error' ? <ErrorNotice error={overview.error} onRetry={overview.reload} /> : null}
      {overview.status === 'success' ? <Body departments={overview.data.departments} reload={overview.reload} /> : null}
    </section>
  )
}

function Body({ departments, reload }: { departments: OverviewDepartment[]; reload: () => void }) {
  const api = useStaffApi()
  const { can } = useStaffSession()
  const canAct = can(ADMIN) // the probe and the trial are admin-only calls
  const action = useAction()
  const [result, setResult] = useState<string | null>(null)

  async function check(code: string) {
    setResult(null)
    const ok = await action.run(`probe:${code}`, async () => {
      const health = await api.probeDataSource(code)
      setResult(`Source ${code} is ${humanize(health.healthStatus)}${health.detail ? `: ${health.detail}` : ''}.`)
    }, '')
    if (ok) reload()
  }

  async function trial(d: OverviewDocument) {
    setResult(null)
    const ok = await action.run(`trial:${d.connectorRef}`, async () => {
      const t = await api.trialConnector(d.connectorRef as string)
      setResult(`Trial for ${humanize(d.category)}: ${t.ok ? 'worked' : `did not work (${humanize(t.outcome)})`}${t.detail ? `. ${t.detail}` : ''}`)
    }, '')
    if (ok) reload()
  }

  async function publish(d: OverviewDocument) {
    const ref = d.connectorRef as string
    setResult(null)
    const ok = await action.run(`publish:${ref}`, async () => {
      const report = await api.testConnector(ref)
      if (report.passed) {
        await api.publishConnector(ref, report)
        setResult(`${ref} passed its test and is published.`)
      } else {
        setResult(`${ref} did not pass its test, so it was not published: ${report.failures.join('; ')}`)
      }
    }, '')
    if (ok) reload()
  }

  if (departments.length === 0) {
    return (
      <div className="card">
        <p>
          <strong>No department has been onboarded yet</strong>
        </p>
        <p className="hint">
          Departments appear here once they are onboarded from their manifest. Go to <Link to="/staff/admin/onboarding">Onboarding</Link>.
        </p>
      </div>
    )
  }

  return (
    <>
      {result ? (
        <p role="status" className="notice">
          {result}
        </p>
      ) : null}
      {action.error ? <ErrorNotice error={action.error} /> : null}
      <ul className="stack plain">
        {departments.map((dept) => (
          <li key={dept.code} className="card">
            <h2 style={{ marginTop: 0 }}>{dept.name}</h2>
            <p>
              <Badge tone="neutral">{dept.code}</Badge>
            </p>
            <dl className="facts">
              <div className="fact">
                <dt>Pinned key fingerprint</dt>
                <dd>{dept.pinnedKeyThumbprint ? <span className="mono">{dept.pinnedKeyThumbprint}</span> : 'No key pinned: its manifest was not signed'}</dd>
              </div>
              <div className="fact">
                <dt>Login address</dt>
                <dd>{dept.loginUrl ? <span className="mono">{dept.loginUrl}</span> : 'No login address given'}</dd>
              </div>
            </dl>

            <h3>Data sources</h3>
            {dept.dataSources.length === 0 ? (
              <p>No data source.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="sr-only">Data sources of {dept.code}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Source</th>
                      <th scope="col">Protocol</th>
                      <th scope="col">Host</th>
                      <th scope="col">Health</th>
                      {canAct ? <th scope="col">Check</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {dept.dataSources.map((s) => (
                      <tr key={s.code}>
                        <td className="mono">{s.code}</td>
                        <td>{s.protocol}</td>
                        <td className="mono">{s.host}</td>
                        <td>
                          <Badge tone={healthTone(s.health)}>{humanize(s.health)}</Badge>
                          {s.healthDetail ? <span className="hint"> {s.healthDetail}</span> : null}
                        </td>
                        {canAct ? (
                          <td>
                            <button type="button" className="btn" disabled={action.busy !== null} onClick={() => void check(s.code)}>
                              {action.busy === `probe:${s.code}` ? 'Checking…' : 'Check source'}
                            </button>
                          </td>
                        ) : null}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <h3>Documents</h3>
            {dept.documents.length === 0 ? (
              <p>No document.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="sr-only">Documents of {dept.code}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Document</th>
                      <th scope="col">Connector</th>
                      <th scope="col">Working</th>
                      <th scope="col">Last trial</th>
                      {canAct ? <th scope="col">Check</th> : null}
                    </tr>
                  </thead>
                  <tbody>
                    {dept.documents.map((d) => {
                      const reason = whyNot(d)
                      return (
                        <tr key={d.category}>
                          <td>
                            {d.title} <span className="hint">{humanize(d.category)}</span>
                          </td>
                          <td>
                            {d.connectorRef ? <span className="mono">{d.connectorRef}</span> : <Badge tone="warn">Not connected</Badge>}{' '}
                            {d.connectorRef ? <Badge tone={publishTone(d.connectorStatus)}>{humanize(d.connectorStatus)}</Badge> : null}
                          </td>
                          <td>
                            <Badge tone={d.working ? 'ok' : 'bad'}>{d.working ? 'Yes' : 'No'}</Badge>
                            {reason ? <p className="hint">{reason}</p> : null}
                            {d.connectorStatus === 'DRAFT' && canAct ? (
                              <p className="hint">
                                <button type="button" className="btn" disabled={action.busy !== null} onClick={() => void publish(d)}>
                                  {action.busy === `publish:${d.connectorRef}` ? 'Testing…' : 'Test and publish'}
                                </button>
                              </p>
                            ) : null}
                          </td>
                          <td>
                            {d.lastTrial ? (
                              <>
                                <Badge tone={d.lastTrial.outcome === 'SUCCESS' ? 'ok' : 'warn'}>
                                  {d.lastTrial.outcome === 'SUCCESS' ? 'Worked' : `Did not work (${humanize(d.lastTrial.outcome)})`}
                                </Badge>{' '}
                                <span className="hint">{formatDateTime(d.lastTrial.at)}</span>
                              </>
                            ) : (
                              <span className="hint">Not run yet</span>
                            )}
                          </td>
                          {canAct ? (
                            <td>
                              {d.connectorRef ? (
                                <button type="button" className="btn" disabled={action.busy !== null} onClick={() => void trial(d)}>
                                  {action.busy === `trial:${d.connectorRef}` ? 'Running…' : 'Run trial'}
                                </button>
                              ) : null}
                            </td>
                          ) : null}
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <h3>Journeys</h3>
            {dept.journeys.length === 0 ? (
              <p>No journey.</p>
            ) : (
              <ul className="plain">
                {dept.journeys.map((j) => (
                  <li key={j.code}>
                    {j.name} <span className="mono">{j.code}</span> <Badge tone={publishTone(j.status)}>{humanize(j.status)}</Badge>{' '}
                    <Link to={`/staff/admin/journeys/${encodeURIComponent(j.code)}`}>Status and log</Link>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </>
  )
}
