import { Link, useParams } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { JourneyStatus, JourneyStatusCategory } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, humanize, shortId } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { Tile } from '../../../ui/Tile'
import { useAsync } from '../../../ui/useAsync'
import { appStatus, stepTone, type Tone } from '../lib/status'
import { OFFICER } from '../nav'
import { useStaffSession } from '../StaffContext'

function healthTone(health: string): Tone {
  return health === 'GREEN' ? 'ok' : health === 'RED' ? 'bad' : health === 'AMBER' ? 'warn' : 'neutral'
}

function journeyTone(status: string): Tone {
  return status === 'PUBLISHED' ? 'ok' : status === 'DRAFT' ? 'warn' : 'neutral'
}

/** Why a category is not working, in plain words; null when it is. */
function whyNot(c: JourneyStatusCategory): string | null {
  if (c.working) return null
  if (!c.connectorRef) return 'No published connector for this document yet'
  return 'The data source is not reachable, so fetches for this document will fail'
}

/**
 * One journey for staff: is each document source connected and working, how its applications are going, and the
 * middle-layer log of the steps those applications ran (GET /api/ops/journeys/{code}; OFFICER, ADMIN).
 */
export function JourneyStatusPage() {
  const { code = '' } = useParams()
  const api = useStaffApi()
  const status = useAsync(() => api.getJourneyStatus(code), `journey-status:${code}`)

  return (
    <section aria-labelledby="js-h">
      {status.status === 'success' ? (
        <Header s={status.data} />
      ) : (
        <h1 id="js-h">
          Journey <span className="mono">{code}</span>
        </h1>
      )}
      <div className="actions">
        <button type="button" className="btn" onClick={status.reload} disabled={status.refreshing}>
          {status.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
        <Link to="/staff/admin/catalog">Back to the catalog</Link>
      </div>
      {status.status === 'loading' ? <Loading variant="table" label="Loading the journey" rows={5} /> : null}
      {status.status === 'error' ? <ErrorNotice error={status.error} onRetry={status.reload} /> : null}
      {status.status === 'success' ? <Body s={status.data} /> : null}
    </section>
  )
}

function Header({ s }: { s: JourneyStatus }) {
  return (
    <>
      <h1 id="js-h">{s.name}</h1>
      <p className="lede">
        <span className="mono">{s.code}</span> <Badge tone={journeyTone(s.status)}>{humanize(s.status)}</Badge>
        {s.requester ? <span className="hint"> run by {s.requester}</span> : null}
      </p>
      {s.portalUrl ? (
        <p>
          <a href={s.portalUrl} target="_blank" rel="noreferrer">
            Open the department portal for this service
          </a>
        </p>
      ) : null}
    </>
  )
}

function Body({ s }: { s: JourneyStatus }) {
  return (
    <>
      <Connected categories={s.categories} />
      <Counts counts={s.counts} />
      <Recent recent={s.recent} />
      <Log log={s.log} />
    </>
  )
}

function Connected({ categories }: { categories: JourneyStatusCategory[] }) {
  const allWorking = categories.length > 0 && categories.every((c) => c.working)
  return (
    <section aria-labelledby="js-conn-h">
      <h2 id="js-conn-h">Connected and working</h2>
      <p>
        <Badge tone={allWorking ? 'ok' : 'warn'}>{allWorking ? 'Every document source is working' : 'Some document sources are not working'}</Badge>
      </p>
      <div className="table-wrap">
        <table>
          <caption className="sr-only">Connected and working</caption>
          <thead>
            <tr>
              <th scope="col">Document</th>
              <th scope="col">Department</th>
              <th scope="col">Connector</th>
              <th scope="col">Source health</th>
              <th scope="col">Last trial</th>
              <th scope="col">Working</th>
            </tr>
          </thead>
          <tbody>
            {categories.map((c) => {
              const reason = whyNot(c)
              return (
                <tr key={c.category}>
                  <td>{humanize(c.category)}</td>
                  <td className="mono">{c.department ?? 'unassigned'}</td>
                  <td>
                    {c.connectorRef ? (
                      <span className="mono">{c.connectorRef}</span>
                    ) : (
                      <Badge tone="warn">Not connected</Badge>
                    )}
                  </td>
                  <td>
                    <Badge tone={healthTone(c.sourceHealth)}>{humanize(c.sourceHealth)}</Badge>
                  </td>
                  <td>
                    {c.lastTrial ? (
                      <>
                        <Badge tone={c.lastTrial.outcome === 'SUCCESS' ? 'ok' : 'warn'}>
                          {c.lastTrial.outcome === 'SUCCESS' ? 'Worked' : `Did not work (${humanize(c.lastTrial.outcome)})`}
                        </Badge>{' '}
                        <span className="hint">{formatDateTime(c.lastTrial.at)}</span>
                      </>
                    ) : (
                      <span className="hint">Not run yet</span>
                    )}
                  </td>
                  <td>
                    <Badge tone={c.working ? 'ok' : 'bad'}>{c.working ? 'Yes' : 'No'}</Badge>
                    {reason ? (
                      <p className="hint">
                        <span>{reason}</span>
                        {!c.connectorRef ? (
                          <>
                            . <Link to="/staff/admin/onboarding">Go to onboarding</Link>
                          </>
                        ) : null}
                      </p>
                    ) : null}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function Counts({ counts }: { counts: JourneyStatus['counts'] }) {
  return (
    <section aria-labelledby="js-counts-h">
      <h2 id="js-counts-h">Applications</h2>
      <div className="tiles">
        <Tile label="Running" value={counts.running} />
        <Tile label="Completed" value={counts.completed} tone={counts.completed > 0 ? 'ok' : undefined} />
        <Tile label="Failed" value={counts.failed} tone={counts.failed > 0 ? 'bad' : undefined} />
        <Tile label="Started in the last 7 days" value={counts.last7Days} />
      </div>
    </section>
  )
}

function Recent({ recent }: { recent: JourneyStatus['recent'] }) {
  const { can } = useStaffSession()
  return (
    <section aria-labelledby="js-recent-h">
      <h2 id="js-recent-h">Recent applications</h2>
      {recent.length === 0 ? (
        <p>No applications have run through this journey yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Recent applications</caption>
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col">State</th>
                <th scope="col">Started</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((r) => {
                const st = appStatus(r.state)
                return (
                  <tr key={r.instanceId}>
                    <td>
                      {r.referenceNo ? (
                        can(OFFICER) ? (
                          <Link to={`/staff/officer/applications/${encodeURIComponent(r.referenceNo)}`}>{r.referenceNo}</Link>
                        ) : (
                          r.referenceNo
                        )
                      ) : (
                        <span className="mono" title={r.instanceId}>
                          {shortId(r.instanceId)}
                        </span>
                      )}
                    </td>
                    <td>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </td>
                    <td>{formatDateTime(r.startedAt)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function Log({ log }: { log: JourneyStatus['log'] }) {
  return (
    <section aria-labelledby="js-log-h">
      <h2 id="js-log-h">Middle-layer log</h2>
      <p className="hint">The steps of the recent applications above, newest first. It shows what Samanvay asked for and how it went, never the citizen&apos;s data.</p>
      {log.length === 0 ? (
        <p>Nothing in the log yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Middle-layer log</caption>
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Application</th>
                <th scope="col">Document</th>
                <th scope="col">Department</th>
                <th scope="col">Connector</th>
                <th scope="col">Outcome</th>
                <th scope="col">Latency</th>
                <th scope="col">Error</th>
              </tr>
            </thead>
            <tbody>
              {log.map((r, i) => {
                const st = stepTone(r.outcome)
                return (
                  <tr key={`${r.referenceNo}-${r.category}-${i}`}>
                    <td>{formatDateTime(r.at) || 'n/a'}</td>
                    <td className="mono">{r.referenceNo}</td>
                    <td>{humanize(r.category)}</td>
                    <td className="mono">{r.department ?? 'n/a'}</td>
                    <td className="mono">{r.connector ?? 'n/a'}</td>
                    <td>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </td>
                    <td>{r.latencyMs === null ? 'n/a' : `${r.latencyMs} ms`}</td>
                    <td className="mono">{r.error ?? ''}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
