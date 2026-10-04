import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { overview } from '../../test/staffFixtures'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const ROUTE = '/staff/admin/journeys'
const PATH = '/api/ops/overview'
const admin = () => staffAuth(['admin'])

const row = (table: HTMLElement, code: string) => within(within(table).getByText(code).closest('tr') as HTMLElement)

/** The text of one row's cell under the column with this header, so a number is asserted against its own label. */
const cell = (table: HTMLElement, code: string, header: string) => {
  const col = within(table).getAllByRole('columnheader').findIndex((h) => h.textContent === header)
  expect(col, `column ${header}`).toBeGreaterThanOrEqual(0)
  return (within(table).getByText(code).closest('tr') as HTMLElement).querySelectorAll('td')[col]?.textContent
}

/** The overview with one department's documents and one journey's needs replaced. */
const withHealth = (health: string, working: boolean, journey: Partial<(typeof overview.departments)[1]['journeys'][0]> = {}) => ({
  ...overview,
  departments: overview.departments.map((d) => ({
    ...d,
    dataSources: d.dataSources.map((s) => ({ ...s, health })),
    documents: d.documents.map((x) => ({ ...x, sourceHealth: health, working })),
    journeys: d.journeys.map((j) => ({ ...j, needs: j.needs.map((n) => ({ ...n, working })), ...(j.code === 'EDUCATION_SCHOLARSHIP' ? journey : {}) })),
  })),
})

describe('staff: onboarded journeys', () => {
  it('lists every onboarded journey with requester, status, readiness, counts and a link to its log', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })

    const ready = row(table, 'EDUCATION_SCHOLARSHIP')
    expect(ready.getByText('Post-matric scholarship')).toBeInTheDocument()
    expect(ready.getByText('EDUCATION')).toBeInTheDocument()
    expect(ready.getByText('Draft')).toBeInTheDocument()
    expect(ready.getByText('Ready to publish')).toBeInTheDocument()
    expect(ready.getByRole('link', { name: 'Status and log' })).toHaveAttribute('href', '/staff/admin/journeys/EDUCATION_SCHOLARSHIP')

    const live = row(table, 'FARMER_SUBSIDY')
    expect(live.getByText('Published')).toBeInTheDocument()
    expect(live.getByText('REVENUE')).toBeInTheDocument()
    // The backend counts APPROVED as completed and REJECTED as failed, so the page says exactly that.
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(
      expect.arrayContaining(['Running', 'Approved', 'Rejected', 'Last 7 days']),
    )
    expect(within(table).queryByRole('columnheader', { name: 'Completed' })).not.toBeInTheDocument()
    expect(within(table).queryByRole('columnheader', { name: 'Failed' })).not.toBeInTheDocument()
    expect(cell(table, 'FARMER_SUBSIDY', 'Running')).toBe('2')
    expect(cell(table, 'FARMER_SUBSIDY', 'Approved')).toBe('5')
    expect(cell(table, 'FARMER_SUBSIDY', 'Rejected')).toBe('1')
    expect(cell(table, 'FARMER_SUBSIDY', 'Last 7 days')).toBe('4')
    expect(live.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('says which documents a draft journey is waiting for, and offers no Publish', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    const waiting = row(table, 'REVENUE_CERT')
    expect(waiting.getByText('Waiting for: Income certificate, Domicile certificate')).toBeInTheDocument()
    expect(waiting.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    // a live journey whose source stopped working says so too
    expect(row(table, 'FARMER_SUBSIDY').getByText('Waiting for: Income certificate')).toBeInTheDocument()
  })

  it('does not say "All sources working" for a published journey whose sources were never checked', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: withHealth('UNKNOWN', true) } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    const live = row(table, 'FARMER_SUBSIDY')
    expect(live.getByText('Not checked yet')).toBeInTheDocument()
    expect(live.queryByText('All sources working')).not.toBeInTheDocument()
  })

  it('says "All sources working" only when the sources were checked and are green', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: withHealth('GREEN', true) } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    expect(row(table, 'FARMER_SUBSIDY').getByText('All sources working')).toBeInTheDocument()
  })

  it('still offers Publish on a ready draft whose sources were not checked, but says so', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: withHealth('UNKNOWN', true) } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const draft = row(await screen.findByRole('table', { name: 'Journeys' }), 'EDUCATION_SCHOLARSHIP')
    expect(draft.getByText('Ready to publish')).toBeInTheDocument()
    expect(draft.getByText('Not checked yet')).toBeInTheDocument()
    expect(draft.getByRole('button', { name: 'Publish' })).toBeInTheDocument()
  })

  it('never shows "Ready to publish" for a draft whose source is RED: it warns and withholds Publish', async () => {
    // ready=true (connectors exist) but the data source is down; the flag may even still say working.
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: withHealth('RED', true) } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const draft = row(await screen.findByRole('table', { name: 'Journeys' }), 'EDUCATION_SCHOLARSHIP')
    expect(draft.queryByText('Ready to publish')).not.toBeInTheDocument()
    expect(draft.getByText('Waiting for: Marks')).toBeInTheDocument()
    expect(draft.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
  })

  it('lets an admin publish a ready draft, then reloads', async () => {
    let published = false
    const m = mockFetch([
      {
        method: 'GET',
        path: PATH,
        reply: () => ({
          body: published
            ? { ...overview, departments: overview.departments.map((d) => ({ ...d, journeys: d.journeys.map((j) => (j.code === 'EDUCATION_SCHOLARSHIP' ? { ...j, status: 'PUBLISHED' } : j)) })) }
            : overview,
        }),
      },
      {
        method: 'POST',
        path: '/api/catalog/journeys/EDUCATION_SCHOLARSHIP/publish',
        reply: () => {
          published = true
          return { body: {} }
        },
      },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    await userEvent.click(row(table, 'EDUCATION_SCHOLARSHIP').getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(m.find('POST', '/api/catalog/journeys/EDUCATION_SCHOLARSHIP/publish')).toHaveLength(1))
    await waitFor(() => expect(row(screen.getByRole('table', { name: 'Journeys' }), 'EDUCATION_SCHOLARSHIP').getByText('Published')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('shows the reason when publishing is refused', async () => {
    const m = mockFetch([
      { method: 'GET', path: PATH, reply: { body: overview } },
      { method: 'POST', path: '/api/catalog/journeys/EDUCATION_SCHOLARSHIP/publish', reply: { status: 400, body: { title: 'Invalid request', detail: 'No published connector for MARKS' } } },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    await userEvent.click(row(table, 'EDUCATION_SCHOLARSHIP').getByRole('button', { name: 'Publish' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/No published connector for MARKS/)
  })

  it('gives an officer the list and the log link but no Publish', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    const table = await screen.findByRole('table', { name: 'Journeys' })
    expect(within(table).getAllByRole('link', { name: 'Status and log' })).toHaveLength(3)
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
  })

  it('says so when no journey has been onboarded, linking to Onboarding', async () => {
    const none = { generatedAt: '2026-10-04T10:00:00Z', departments: [{ ...overview.departments[0]!, journeys: [] }] }
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: none } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByText('No journey has been onboarded yet')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getAllByRole('link', { name: 'Onboarding' })[0]).toHaveAttribute('href', '/staff/admin/onboarding')
    expect(screen.queryByRole('table', { name: 'Journeys' })).not.toBeInTheDocument()
  })

  it('surfaces a failure and can retry', async () => {
    let fail = true
    const m = mockFetch([{ method: 'GET', path: PATH, reply: () => (fail ? { status: 500, body: { status: 500 } } : { body: overview }) }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'Journeys' })).toBeInTheDocument()
  })

  it('still opens one journey at /staff/admin/journeys/:code', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/ops/journeys/EDUCATION_SCHOLARSHIP', reply: { status: 500, body: { status: 500 } } }])
    renderStaff({ route: `${ROUTE}/EDUCATION_SCHOLARSHIP`, fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByRole('heading', { name: /Journey/ })).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Journeys' })).not.toBeInTheDocument()
  })
})
