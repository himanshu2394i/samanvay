import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { overview } from '../../test/staffFixtures'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const ROUTE = '/staff/admin/journeys'
const PATH = '/api/ops/overview'
const admin = () => staffAuth(['admin'])

const row = (table: HTMLElement, code: string) => within(within(table).getByText(code).closest('tr') as HTMLElement)

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
    // running, completed, failed, last 7 days
    expect(live.getAllByRole('cell').map((c) => c.textContent)).toEqual(expect.arrayContaining(['2', '5', '1', '4']))
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
