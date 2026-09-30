import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { DataSourceHealth } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'

function statusTone(status: string): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (status) {
    case 'ACTIVE':
    case 'PUBLISHED':
      return 'ok'
    case 'DRAFT':
      return 'warn'
    case 'RETIRED':
    case 'DEPRECATED':
      return 'neutral'
    default:
      return 'neutral'
  }
}

function healthTone(status: string): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (status) {
    case 'GREEN':
      return 'ok'
    case 'RED':
      return 'bad'
    case 'AMBER':
      return 'warn'
    default:
      return 'neutral'
  }
}

/** Read-only view of the catalog: departments, journeys (services) and connectors. */
export function CatalogPage() {
  const api = useStaffApi()
  const data = useAsync(async () => {
    const [departments, journeys, connectors] = await Promise.all([api.listDepartments(), api.listJourneys(), api.listConnectors()])
    return { departments, journeys, connectors }
  }, 'catalog')

  return (
    <section aria-labelledby="cat-h">
      <h1 id="cat-h">Catalog</h1>
      <p className="lede">
        What Samanvay can reach and which services it offers. To add a department or connector use{' '}
        <Link to="/staff/admin/onboarding">Onboarding</Link>.
      </p>
      <div className="actions">
        <button type="button" className="btn" onClick={data.reload}>
          Refresh
        </button>
      </div>
      {data.status === 'loading' ? <Loading label="Loading the catalog" /> : null}
      {data.status === 'error' ? <ErrorNotice error={data.error} onRetry={data.reload} /> : null}
      {data.status === 'success' ? (
        <>
          <h2>Journeys</h2>
          {data.data.journeys.length === 0 ? (
            <p>No journeys are registered.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Journeys</caption>
                <thead>
                  <tr>
                    <th scope="col">Code</th>
                    <th scope="col">Name</th>
                    <th scope="col">Status</th>
                    <th scope="col">SLA</th>
                    <th scope="col">Requester</th>
                    <th scope="col">Purpose</th>
                    <th scope="col">Records needed (department)</th>
                  </tr>
                </thead>
                <tbody>
                  {data.data.journeys.map((j) => (
                    <tr key={j.code}>
                      <td className="mono">{j.code}</td>
                      <td>{j.name}</td>
                      <td>
                        <Badge tone={statusTone(j.status)}>{humanize(j.status)}</Badge>
                      </td>
                      <td>{j.policy.slaHours} h</td>
                      <td>{j.policy.requester}</td>
                      <td className="mono">{j.policy.purpose}</td>
                      <td>
                        <ul className="plain">
                          {j.requiredCategories.map((c) => (
                            <li key={c}>
                              {humanize(c)} <span className="hint">({j.policy.sources[c] ?? 'unassigned'})</span>
                            </li>
                          ))}
                        </ul>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h2>Departments</h2>
          {data.data.departments.length === 0 ? (
            <p>No departments are registered.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Departments</caption>
                <thead>
                  <tr>
                    <th scope="col">Code</th>
                    <th scope="col">Name</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {data.data.departments.map((d) => (
                    <tr key={d.code}>
                      <td className="mono">{d.code}</td>
                      <td>{d.name}</td>
                      <td>
                        <Badge tone={statusTone(d.status)}>{humanize(d.status)}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h2>Connectors</h2>
          {data.data.connectors.length === 0 ? (
            <p>No published connectors.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Connectors</caption>
                <thead>
                  <tr>
                    <th scope="col">Ref</th>
                    <th scope="col">Connector</th>
                    <th scope="col">Version</th>
                    <th scope="col">Data source</th>
                    <th scope="col">Document type</th>
                    <th scope="col">Status</th>
                    <th scope="col">SLA</th>
                  </tr>
                </thead>
                <tbody>
                  {data.data.connectors.map((c) => (
                    <tr key={c.ref}>
                      <td className="mono">{c.ref}</td>
                      <td className="mono">{c.connectorId}</td>
                      <td>{c.version}</td>
                      <td className="mono">{c.dataSourceCode}</td>
                      <td>{humanize(c.category.code)}</td>
                      <td>
                        <Badge tone={statusTone(c.status)}>{humanize(c.status)}</Badge>
                      </td>
                      <td>{c.slaMs === null ? 'n/a' : `${c.slaMs} ms`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <DataSourcesPanel />
        </>
      ) : null}
    </section>
  )
}

/** Data sources with a live connectivity check (the "onboard it and see it connect" proof). */
function DataSourcesPanel() {
  const api = useStaffApi()
  const data = useAsync(() => api.listDataSources(), 'data-sources')
  const probe = useAction()
  const [health, setHealth] = useState<Record<string, DataSourceHealth>>({})

  async function check(code: string) {
    let r: DataSourceHealth | undefined
    const ok = await probe.run(code, async () => {
      r = await api.probeDataSource(code)
    }, '')
    if (ok && r) setHealth((h) => ({ ...h, [code]: r as DataSourceHealth }))
  }

  return (
    <>
      <h2 className="spaced">Data sources &amp; connectivity</h2>
      <p className="hint">Where Samanvay reaches each department. &ldquo;Check&rdquo; makes a live connection to the source right now.</p>
      {data.status === 'loading' ? <Loading variant="table" label="Loading data sources" /> : null}
      {data.status === 'error' ? <ErrorNotice error={data.error} onRetry={data.reload} /> : null}
      {probe.error ? <ErrorNotice error={probe.error} /> : null}
      {data.status === 'success' ? (
        data.data.length === 0 ? (
          <p>No data sources are registered.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Data sources and their connectivity health</caption>
              <thead>
                <tr>
                  <th scope="col">Source</th>
                  <th scope="col">Department</th>
                  <th scope="col">Protocol</th>
                  <th scope="col">Host</th>
                  <th scope="col">Health</th>
                  <th scope="col">Check</th>
                </tr>
              </thead>
              <tbody>
                {data.data.map((s) => {
                  const h = health[s.code] ?? s
                  return (
                    <tr key={s.code}>
                      <td className="mono">{s.code}</td>
                      <td className="mono">{s.departmentCode}</td>
                      <td>{s.protocol}</td>
                      <td className="mono">{s.baseHost}</td>
                      <td>
                        <Badge tone={healthTone(h.healthStatus)}>{humanize(h.healthStatus)}</Badge>
                        {health[s.code]?.detail ? <span className="hint"> {health[s.code]?.detail}</span> : null}
                      </td>
                      <td>
                        <button type="button" className="btn" onClick={() => void check(s.code)} disabled={probe.busy !== null}>
                          {probe.busy === s.code ? 'Checking…' : 'Check'}
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}
    </>
  )
}
