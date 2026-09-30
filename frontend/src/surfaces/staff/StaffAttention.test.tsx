import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const METRICS = {
  generatedAt: '2026-09-29T00:00:00Z',
  connector: { latencyWindowMinutes: 60, sources: [] },
  sla: { open: 5, breached: 2, dueSoon: 3, withinSlaPercent: 90, byJourney: [], watchlist: [] },
  consent: { granted: 0, denied: 0, grantRate: null, denialsByReason: [] },
  exceptionQueue: { open: 4, oldestAgeSeconds: null, byReason: [], oldest: [] },
}

const BANK_REVIEWS = [
  { id: 'r1', instanceId: 'i1', accountMasked: 'XXXX1234', status: 'PENDING' },
  { id: 'r2', instanceId: 'i2', accountMasked: 'XXXX5678', status: 'PENDING' },
]

const routes = [
  { method: 'GET', path: /\/api\/ops\/metrics$/, reply: { body: METRICS } },
  { method: 'GET', path: /\/api\/officer\/bank-reviews$/, reply: { body: BANK_REVIEWS } },
]

describe('staff home — needs attention', () => {
  it('shows the live attention tiles for an officer, linking into each console', async () => {
    const { fetchImpl } = mockFetch(routes)
    renderStaff({ route: '/staff', fetchImpl, auth: staffAuth(['officer']) })

    const region = await screen.findByRole('region', { name: 'Needs attention now' })
    const attention = within(region)

    // each tile is a link whose accessible name carries its label + value
    expect(attention.getByRole('link', { name: /Open exceptions 4/ })).toHaveAttribute(
      'href',
      '/staff/officer/exceptions',
    )
    expect(attention.getByRole('link', { name: /SLA breached 2/ })).toHaveAttribute('href', '/staff/ops/metrics')
    expect(attention.getByRole('link', { name: /Due soon 3/ })).toHaveAttribute('href', '/staff/ops/metrics')
    expect(attention.getByRole('link', { name: /Bank reviews 2/ })).toHaveAttribute(
      'href',
      '/staff/officer/bank-reviews',
    )
  })

  it('does not show the attention strip for a reviewer, who has no operational figures', async () => {
    const { fetchImpl } = mockFetch(routes)
    renderStaff({ route: '/staff', fetchImpl, auth: staffAuth(['reviewer']) })

    // the home still renders, with the reviewer's own console card
    expect(await screen.findByRole('heading', { name: 'Staff consoles' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Identity review' }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('region', { name: 'Needs attention now' })).not.toBeInTheDocument()
  })
})
