import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { JourneyStatus } from '../../api/staffTypes'
import { connector } from '../../test/staffFixtures'
import { DEPARTMENTS, DRAFT_JOURNEY, mockFetch, renderStaff, SCHOLARSHIP, staffAuth } from '../../test/utils'

const PATH = '/api/ops/journeys/POST_MATRIC_SCHOLARSHIP'
const ROUTE = '/staff/admin/journeys/POST_MATRIC_SCHOLARSHIP'

const status: JourneyStatus = {
  code: 'POST_MATRIC_SCHOLARSHIP',
  name: 'Post-matric scholarship',
  requester: 'SCHOLARSHIP',
  status: 'PUBLISHED',
  portalUrl: 'https://scholarship.example.gov/apply',
  categories: [
    {
      category: 'INCOME_CERTIFICATE',
      department: 'REVENUE',
      connectorRef: 'rev-income@3',
      connectorStatus: 'PUBLISHED',
      dataSourceCode: 'revenue-rest',
      sourceHealth: 'GREEN',
      lastTrial: { at: '2026-10-04T09:30:00Z', outcome: 'SUCCESS' },
      working: true,
    },
    {
      category: 'MARKS',
      department: 'EDUCATION',
      connectorRef: 'edu-marks@1',
      connectorStatus: 'PUBLISHED',
      dataSourceCode: 'education-rest',
      sourceHealth: 'RED',
      lastTrial: null,
      working: false,
    },
    {
      category: 'DOMICILE_CERTIFICATE',
      department: 'REVENUE',
      connectorRef: null,
      connectorStatus: 'NONE',
      dataSourceCode: null,
      sourceHealth: 'UNKNOWN',
      lastTrial: null,
      working: false,
    },
  ],
  counts: { running: 2, completed: 5, failed: 1, last7Days: 4 },
  recent: [
    { instanceId: '22222222-2222-4222-8222-222222222222', referenceNo: 'SCH-2026-0001', state: 'VERIFIED', startedAt: '2026-10-04T08:00:00Z' },
  ],
  log: [
    {
      at: '2026-10-04T08:00:01Z',
      referenceNo: 'SCH-2026-0001',
      category: 'INCOME_CERTIFICATE',
      department: 'REVENUE',
      connector: 'rev-income@3',
      outcome: 'COMPLETED',
      latencyMs: 250,
      error: null,
    },
    {
      at: '2026-10-04T08:00:02Z',
      referenceNo: 'SCH-2026-0001',
      category: 'MARKS',
      department: 'EDUCATION',
      connector: 'edu-marks@1',
      outcome: 'FAILED',
      latencyMs: 1200,
      error: 'NOT_FOUND',
    },
  ],
}

const admin = () => staffAuth(['admin'])
const both = () => staffAuth(['admin', 'officer'])

describe('staff: journey status page', () => {
  it('shows the header, each category with its connector, health and trial, the counts and the log', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: status } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: both() })

    expect(await screen.findByRole('heading', { level: 1, name: 'Post-matric scholarship' })).toBeInTheDocument()
    expect(screen.getByText('Published')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open the department portal/ })).toHaveAttribute('href', 'https://scholarship.example.gov/apply')

    const panel = screen.getByRole('table', { name: 'Connected and working' })
    const income = within(within(panel).getByText('Income certificate').closest('tr') as HTMLElement)
    expect(income.getByText('REVENUE')).toBeInTheDocument()
    expect(income.getByText('rev-income@3')).toBeInTheDocument()
    expect(income.getByText('Green')).toBeInTheDocument()
    expect(income.getByText(/Worked/)).toBeInTheDocument()
    expect(income.getByText('Yes')).toBeInTheDocument()

    const marks = within(within(panel).getByText('Marks').closest('tr') as HTMLElement)
    expect(marks.getByText('Red')).toBeInTheDocument()
    expect(marks.getByText('No')).toBeInTheDocument()
    expect(marks.getByText('Not run yet')).toBeInTheDocument()
    expect(marks.getByText(/data source is not reachable/i)).toBeInTheDocument()

    expect(screen.getByText('Running')).toBeInTheDocument()
    expect(screen.getByText('Started in the last 7 days')).toBeInTheDocument()

    const recent = screen.getByRole('table', { name: 'Recent applications' })
    expect(within(recent).getByRole('link', { name: 'SCH-2026-0001' })).toHaveAttribute('href', '/staff/officer/applications/SCH-2026-0001')

    const log = screen.getByRole('table', { name: 'Middle-layer log' })
    expect(within(log).getByText('250 ms')).toBeInTheDocument()
    expect(within(log).getByText('NOT_FOUND')).toBeInTheDocument()
    expect(within(log).getByText('edu-marks@1')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('explains a category with no published connector and links to onboarding', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: status } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    const panel = await screen.findByRole('table', { name: 'Connected and working' })
    const domicile = within(within(panel).getByText('Domicile certificate').closest('tr') as HTMLElement)
    expect(domicile.getByText('No published connector for this document yet')).toBeInTheDocument()
    expect(domicile.getByRole('link', { name: 'Go to onboarding' })).toHaveAttribute('href', '/staff/admin/onboarding')
    expect(domicile.getByText('Not connected')).toBeInTheDocument()
  })

  it('shows an empty state when the journey has not run yet', async () => {
    const quiet: JourneyStatus = { ...status, counts: { running: 0, completed: 0, failed: 0, last7Days: 0 }, recent: [], log: [] }
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: quiet } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    expect(await screen.findByText('No applications have run through this journey yet.')).toBeInTheDocument()
    expect(screen.getByText('Nothing in the log yet.')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Middle-layer log' })).not.toBeInTheDocument()
  })

  it('shows an application reference as plain text to an admin who cannot open officer pages', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: status } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const recent = await screen.findByRole('table', { name: 'Recent applications' })
    expect(within(recent).getByText('SCH-2026-0001')).toBeInTheDocument()
    expect(within(recent).queryByRole('link')).not.toBeInTheDocument()
  })

  it('surfaces a failure and can retry', async () => {
    let fail = true
    const m = mockFetch([{ method: 'GET', path: PATH, reply: () => (fail ? { status: 500, body: { status: 500 } } : { body: status }) }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Post-matric scholarship' })).toBeInTheDocument()
  })

  it('says so for an unknown journey', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { status: 404, body: { status: 404 } } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('refreshes on demand', async () => {
    let calls = 0
    const m = mockFetch([{ method: 'GET', path: PATH, reply: () => { calls += 1; return { body: status } } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    await screen.findByRole('heading', { level: 1, name: 'Post-matric scholarship' })
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await screen.findByRole('button', { name: 'Refresh' })
    expect(calls).toBe(2)
  })

  it('is open to an officer, and not to a citizen-only or reviewer session', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: status } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    expect(await screen.findByRole('heading', { level: 1, name: 'Post-matric scholarship' })).toBeInTheDocument()
  })

  it('is refused for a reviewer', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: status } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: staffAuth(['reviewer']) })
    expect(await screen.findByText(/not available|do not have access|not allowed/i)).toBeInTheDocument()
    expect(m.find('GET', PATH)).toHaveLength(0)
  })
})

describe('catalog links to the journey status page', () => {
  it('gives each journey row a Status link', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP, DRAFT_JOURNEY] } },
      { method: 'GET', path: '/api/catalog/departments', reply: { body: DEPARTMENTS } },
      { method: 'GET', path: '/api/catalog/connectors', reply: { body: [connector] } },
      { method: 'GET', path: '/api/catalog/data-sources', reply: { body: [] } },
    ])
    renderStaff({ route: '/staff/admin/catalog', fetchImpl: m.fetchImpl, auth: admin() })

    const journeys = await screen.findByRole('table', { name: 'Journeys' })
    const link = within(within(journeys).getByText('POST_MATRIC_SCHOLARSHIP').closest('tr') as HTMLElement).getByRole('link', { name: 'Status' })
    expect(link).toHaveAttribute('href', '/staff/admin/journeys/POST_MATRIC_SCHOLARSHIP')
    expect(within(journeys).getAllByRole('link', { name: 'Status' })).toHaveLength(2)
  })
})
