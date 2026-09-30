import { Link } from 'react-router-dom'
import { useStaffApi } from '../../api/apiContext'
import { useAsync } from '../../ui/useAsync'
import { useStaffSession } from './StaffContext'
import { OFFICER, OPS } from './nav'

interface Tile {
  label: string
  value: number
  tone: '' | 'ok' | 'warn' | 'bad'
  sub: string
  to: string
}

/**
 * The "needs attention now" strip at the top of the staff home: the operational figures a
 * clerk acts on first, each linking into the console that resolves it. Live figures come
 * from the same ops metrics + bank-review endpoints the consoles use, scoped to the roles
 * the signed-in user actually has. If they can't be loaded the home still works — the strip
 * degrades to a quiet line and the console cards below remain.
 */
export function StaffAttention() {
  const api = useStaffApi()
  const { can } = useStaffSession()
  const canOps = can(OPS)
  const canOfficer = can(OFFICER)

  const data = useAsync(async () => {
    const [metrics, bank] = await Promise.all([
      canOps ? api.getMetrics() : Promise.resolve(null),
      canOfficer ? api.listBankReviews().catch(() => null) : Promise.resolve(null),
    ])
    return { metrics, bankCount: bank?.length ?? null }
  }, `${canOps}-${canOfficer}`)

  if (data.status === 'loading') {
    return (
      <p className="hint" aria-live="polite">
        Loading what needs attention…
      </p>
    )
  }
  if (data.status === 'error') {
    return (
      <p className="hint">
        Live figures are unavailable right now. Open a console below to see the latest.
      </p>
    )
  }

  const { metrics, bankCount } = data.data
  const tiles: Tile[] = []
  if (metrics) {
    const openExceptions = metrics.exceptionQueue?.open ?? 0
    const breached = metrics.sla?.breached ?? 0
    const dueSoon = metrics.sla?.dueSoon ?? 0
    tiles.push({
      label: 'Open exceptions',
      value: openExceptions,
      tone: openExceptions > 0 ? 'bad' : 'ok',
      sub: openExceptions > 0 ? 'journeys stopped on a record' : 'nothing stopped',
      to: canOfficer ? '/staff/officer/exceptions' : '/staff/ops/metrics',
    })
    tiles.push({
      label: 'SLA breached',
      value: breached,
      tone: breached > 0 ? 'bad' : 'ok',
      sub: breached > 0 ? 'past the decision deadline' : 'all within time',
      to: '/staff/ops/metrics',
    })
    tiles.push({
      label: 'Due soon',
      value: dueSoon,
      tone: dueSoon > 0 ? 'warn' : '',
      sub: 'approaching the deadline',
      to: '/staff/ops/metrics',
    })
  }
  if (canOfficer && bankCount !== null) {
    tiles.push({
      label: 'Bank reviews',
      value: bankCount,
      tone: bankCount > 0 ? 'warn' : 'ok',
      sub: bankCount > 0 ? 'waiting for an officer' : 'queue clear',
      to: '/staff/officer/bank-reviews',
    })
  }

  if (tiles.length === 0) return null

  return (
    <section aria-labelledby="attention-h" className="spaced">
      <h2 id="attention-h">Needs attention now</h2>
      <div className="tiles">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to} className="tile">
            <p className="tile-label">{t.label}</p>
            <p className={`tile-value ${t.tone}`.trim()}>{t.value}</p>
            <p className="tile-sub">{t.sub}</p>
          </Link>
        ))}
      </div>
    </section>
  )
}
