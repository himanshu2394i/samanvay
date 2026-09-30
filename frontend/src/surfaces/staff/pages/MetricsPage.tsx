import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { ConnectorSource, OpsMetrics } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { formatDateTime, formatDuration, formatMs, formatPercent, formatRatio, humanize, shortId } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { Tile } from '../../../ui/Tile'
import { useAsync } from '../../../ui/useAsync'
import { appStatus } from '../lib/status'
import { useStaffSession } from '../StaffContext'
import { OFFICER } from '../nav'

/** The four ops dashboards from GET /api/ops/metrics (OFFICER, ADMIN). */
export function MetricsPage() {
  const api = useStaffApi()
  const metrics = useAsync(() => api.getMetrics(), 'metrics')

  return (
    <section aria-labelledby="metrics-h">
      <h1 id="metrics-h">Metrics</h1>
      <p className="lede">Connector health, SLA, consent decisions and the exception queue. Numbers that cannot be computed yet show as n/a, not zero.</p>
      <div className="actions">
        <button type="button" className="btn" onClick={metrics.reload} disabled={metrics.refreshing}>
          {metrics.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
        {metrics.status === 'success' ? <span className="hint">As of {formatDateTime(metrics.data.generatedAt)}</span> : null}
      </div>
      {metrics.status === 'loading' ? <Loading label="Loading metrics" /> : null}
      {metrics.status === 'error' ? <ErrorNotice error={metrics.error} onRetry={metrics.reload} /> : null}
      {metrics.status === 'success' ? <Dashboards m={metrics.data} /> : null}
    </section>
  )
}

function Dashboards({ m }: { m: OpsMetrics }) {
  return (
    <>
      <SlaSection sla={m.sla} />
      <ExceptionSection q={m.exceptionQueue} />
      <ConnectorSection windowMinutes={m.connector.latencyWindowMinutes} sources={m.connector.sources} />
      <ConsentSection c={m.consent} />
      {m.notifications ? <NotificationSection n={m.notifications} /> : null}
    </>
  )
}

function NotificationSection({ n }: { n: NonNullable<OpsMetrics['notifications']> }) {
  return (
    <section aria-labelledby="notif-h">
      <h2 id="notif-h">Notification delivery</h2>
      <p className="hint">First-attempt delivery outcomes and retries since this node started.</p>
      <div className="tiles">
        <Tile label="Sent" value={n.sent} />
        <Tile label="Failed" value={n.failed} tone={n.failed > 0 ? 'warn' : 'ok'} />
        <Tile label="Retries sent" value={n.retriedSent} />
        <Tile label="Retries failed" value={n.retriedFailed} tone={n.retriedFailed > 0 ? 'warn' : 'ok'} />
        <Tile label="First-attempt success rate" value={formatRatio(n.sentRate)} />
      </div>
    </section>
  )
}

function SlaSection({ sla }: { sla: OpsMetrics['sla'] }) {
  const { can } = useStaffSession()
  return (
    <section aria-labelledby="sla-h">
      <h2 id="sla-h">SLA</h2>
      <div className="tiles">
        <Tile label="Open applications" value={sla.open} />
        <Tile label="Breached" value={sla.breached} tone={sla.breached > 0 ? 'bad' : 'ok'} />
        <Tile label="Due within 24h" value={sla.dueSoon} tone={sla.dueSoon > 0 ? 'warn' : 'ok'} />
        <Tile label="Within SLA" value={formatPercent(sla.withinSlaPercent)} />
      </div>
      {sla.byJourney.length > 0 ? (
        <div className="table-wrap">
          <table>
            <caption>By service</caption>
            <thead>
              <tr>
                <th scope="col">Service</th>
                <th scope="col">Open</th>
                <th scope="col">Breached</th>
                <th scope="col">Due soon</th>
              </tr>
            </thead>
            <tbody>
              {sla.byJourney.map((j) => (
                <tr key={j.journeyCode}>
                  <td className="mono">{j.journeyCode}</td>
                  <td>{j.open}</td>
                  <td>{j.breached}</td>
                  <td>{j.dueSoon}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <h3 className="spaced">Watchlist</h3>
      {sla.watchlist.length === 0 ? (
        <p>No open applications with an SLA due time.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Applications closest to or past their SLA</caption>
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col">Service</th>
                <th scope="col">Status</th>
                <th scope="col">SLA due</th>
                <th scope="col">Time</th>
              </tr>
            </thead>
            <tbody>
              {sla.watchlist.map((c) => {
                const st = appStatus(c.status)
                const overdue = c.secondsToDue < 0
                return (
                  <tr key={c.referenceNo}>
                    <td>
                      {can(OFFICER) ? <Link to={`/staff/officer/applications/${encodeURIComponent(c.referenceNo)}`}>{c.referenceNo}</Link> : c.referenceNo}
                    </td>
                    <td className="mono">{c.journeyCode}</td>
                    <td>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </td>
                    <td>{formatDateTime(c.slaDueAt) || 'n/a'}</td>
                    <td>
                      <Badge tone={overdue ? 'bad' : 'ok'}>{overdue ? `${formatDuration(c.secondsToDue)} overdue` : `${formatDuration(c.secondsToDue)} left`}</Badge>
                    </td>
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

function ExceptionSection({ q }: { q: OpsMetrics['exceptionQueue'] }) {
  const { can } = useStaffSession()
  return (
    <section aria-labelledby="exq-h">
      <h2 id="exq-h">Exception queue</h2>
      <div className="tiles">
        <Tile label="Open exceptions" value={q.open ?? 'n/a'} tone={q.open ? 'warn' : q.open === 0 ? 'ok' : undefined} />
        <Tile label="Oldest" value={q.oldestAgeSeconds === null ? 'n/a' : formatDuration(q.oldestAgeSeconds)} />
      </div>
      {q.byReason.length > 0 ? (
        <ul className="plain chips" aria-label="Exceptions by reason">
          {q.byReason.map((r) => (
            <li key={r.reason}>
              <Badge tone="warn">
                {r.reason}: {r.count}
              </Badge>
            </li>
          ))}
        </ul>
      ) : null}
      {q.oldest.length > 0 ? (
        <div className="table-wrap">
          <table>
            <caption>Oldest open exceptions</caption>
            <thead>
              <tr>
                <th scope="col">Raised</th>
                <th scope="col">Step</th>
                <th scope="col">Reason</th>
                <th scope="col">Instance</th>
                <th scope="col">Age</th>
              </tr>
            </thead>
            <tbody>
              {q.oldest.map((x) => (
                <tr key={x.id}>
                  <td>{formatDateTime(x.createdAt)}</td>
                  <td className="mono">{x.stepCode}</td>
                  <td>{x.reason}</td>
                  <td className="mono" title={x.instanceId}>
                    {shortId(x.instanceId)}
                  </td>
                  <td>{formatDuration(x.ageSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p>The queue is empty.</p>
      )}
      {can(OFFICER) ? (
        <p>
          <Link to="/staff/officer/exceptions">Work the exception queue</Link>
        </p>
      ) : null}
    </section>
  )
}

function sourceTone(s: ConnectorSource): 'ok' | 'warn' | 'bad' | 'neutral' {
  if (s.successRate === null) return 'neutral'
  if (s.successRate >= 0.99) return 'ok'
  return s.successRate >= 0.9 ? 'warn' : 'bad'
}

function ConnectorSection({ windowMinutes, sources }: { windowMinutes: number; sources: ConnectorSource[] }) {
  return (
    <section aria-labelledby="conn-h">
      <h2 id="conn-h">Connector health</h2>
      <p className="hint">
        Call counts are since this node started. Latency covers successful calls in the last {windowMinutes} minutes on this node.
      </p>
      {sources.length === 0 ? (
        <p>No connector calls recorded yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Connector health per data source</caption>
            <thead>
              <tr>
                <th scope="col">Data source</th>
                <th scope="col">Calls</th>
                <th scope="col">Success</th>
                <th scope="col">Failed</th>
                <th scope="col">Unavailable</th>
                <th scope="col">Success rate</th>
                <th scope="col">p50</th>
                <th scope="col">p95</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.source}>
                  <td className="mono">{s.source}</td>
                  <td>{s.calls}</td>
                  <td>{s.success}</td>
                  <td>{s.failure}</td>
                  <td>{s.unavailable}</td>
                  <td>
                    <Badge tone={sourceTone(s)}>{formatRatio(s.successRate)}</Badge>
                  </td>
                  <td>{formatMs(s.latency?.p50Ms)}</td>
                  <td>{formatMs(s.latency?.p95Ms)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function ConsentSection({ c }: { c: OpsMetrics['consent'] }) {
  return (
    <section aria-labelledby="consent-h">
      <h2 id="consent-h">Consent decisions</h2>
      <p className="hint">Access authorisations since this node started.</p>
      <div className="tiles">
        <Tile label="Granted" value={c.granted} />
        <Tile label="Denied" value={c.denied} tone={c.denied > 0 ? 'warn' : undefined} />
        <Tile label="Grant rate" value={formatRatio(c.grantRate)} />
      </div>
      {c.denialsByReason.length > 0 ? (
        <div className="table-wrap">
          <table>
            <caption>Denials by reason</caption>
            <thead>
              <tr>
                <th scope="col">Reason</th>
                <th scope="col">Count</th>
              </tr>
            </thead>
            <tbody>
              {c.denialsByReason.map((r) => (
                <tr key={r.reason}>
                  <td>{humanize(r.reason)}</td>
                  <td>{r.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
