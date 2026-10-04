import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderStaff, signedInAuth, signedOutAuth, staffAuth } from '../../test/utils'

const EMPTY = mockFetch([]).fetchImpl

// Every staff route family and the role sets that may open it (mirrors SecurityConfig).
const PAGES: { route: string; heading: string; roles: string[] }[] = [
  { route: '/staff/officer/exceptions', heading: 'Exceptions', roles: ['officer'] },
  { route: '/staff/officer/bank-reviews', heading: 'Bank-account reviews', roles: ['officer'] },
  { route: '/staff/officer/applications', heading: 'Applications', roles: ['officer'] },
  { route: '/staff/ops/metrics', heading: 'Metrics', roles: ['officer', 'admin'] },
  { route: '/staff/ops/audit', heading: 'Audit ledger', roles: ['officer', 'admin'] },
  { route: '/staff/admin/departments', heading: 'Departments', roles: ['officer', 'admin'] },
  { route: '/staff/admin/journeys', heading: 'Journeys', roles: ['officer', 'admin'] },
  { route: '/staff/admin/onboarding', heading: 'Onboarding', roles: ['admin'] },
  { route: '/staff/admin/schemas', heading: 'Central schema', roles: ['admin'] },
  { route: '/staff/reviewer/queue', heading: 'Identity review', roles: ['reviewer'] },
  // routes with a parameter: the same guard has to hold on the deep links, not just the list pages
  { route: '/staff/officer/applications/SCH-2026-0001', heading: 'Application SCH-2026-0001', roles: ['officer'] },
  { route: '/staff/officer/citizens/11111111-1111-4111-8111-111111111111', heading: 'Citizen file', roles: ['officer'] },
  { route: '/staff/admin/journeys/FARMER_SUBSIDY', heading: 'Journey FARMER_SUBSIDY', roles: ['officer', 'admin'] },
]

describe('staff route guards', () => {
  it('asks a signed-out visitor to sign in and shows no staff page', async () => {
    renderStaff({ route: '/staff/admin/departments', fetchImpl: EMPTY, auth: signedOutAuth() })
    expect(await screen.findByRole('heading', { name: 'Sign in to continue' })).toBeInTheDocument()
    expect(screen.getByText(/passkey, or with a password and an authenticator code/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Departments' })).not.toBeInTheDocument()
  })

  it('starts the staff sign-in from the prompt, returning to the page that was asked for', async () => {
    const { auth } = renderStaff({ route: '/staff/ops/metrics', fetchImpl: EMPTY, auth: signedOutAuth() })
    const prompt = await screen.findByRole('heading', { name: 'Sign in to continue' })
    await userEvent.click(within(prompt.closest('section')!).getByRole('button', { name: 'Sign in' }))
    expect(auth.signIn).toHaveBeenCalledWith('/staff/ops/metrics')
  })

  it('keeps a citizen token out of every staff surface', async () => {
    for (const p of [PAGES[0]!, PAGES[3]!, PAGES[5]!, PAGES[8]!]) {
      const fetchImpl = mockFetch([])
      const { unmount } = renderStaff({ route: p.route, fetchImpl: fetchImpl.fetchImpl, auth: signedInAuth() })
      expect(await screen.findByRole('heading', { name: 'No staff access' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: p.heading })).not.toBeInTheDocument()
      expect(fetchImpl.calls).toHaveLength(0) // not even an API call is attempted
      unmount()
    }
  })

  it('keeps a staff-realm account with no staff role out too', async () => {
    renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['default-roles-samanvay-staff', 'department']) })
    expect(await screen.findByRole('heading', { name: 'No staff access' })).toBeInTheDocument()
  })

  it.each(PAGES)('$route opens only for $roles', async ({ route, heading, roles }) => {
    for (const role of ['officer', 'admin', 'reviewer']) {
      const fetchImpl = mockFetch([])
      const { unmount } = renderStaff({ route, fetchImpl: fetchImpl.fetchImpl, auth: staffAuth([role]) })
      if (roles.includes(role)) {
        expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
        expect(screen.queryByRole('heading', { name: 'Not available for your role' })).not.toBeInTheDocument()
      } else {
        expect(await screen.findByRole('heading', { name: 'Not available for your role' })).toBeInTheDocument()
        expect(screen.queryByRole('heading', { level: 1, name: heading })).not.toBeInTheDocument()
        // a refused route never reaches the API
        expect(fetchImpl.calls).toHaveLength(0)
      }
      unmount()
    }
  })

  it('shows each role only its own navigation and home cards', async () => {
    const { unmount } = renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['officer']) })
    const nav = await screen.findByRole('navigation', { name: 'Staff' })
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Exceptions', 'Bank reviews', 'Applications', 'Metrics', 'Audit ledger', 'Departments', 'Journeys'])
    unmount()

    const admin = renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['admin']) })
    const adminNav = await screen.findByRole('navigation', { name: 'Staff' })
    expect(within(adminNav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Metrics', 'Audit ledger', 'Departments', 'Journeys', 'Central schema', 'Onboarding'])
    admin.unmount()

    renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['reviewer']) })
    const revNav = await screen.findByRole('navigation', { name: 'Staff' })
    expect(within(revNav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Identity review'])
  })

  it('an account with several roles gets the union', async () => {
    renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['officer', 'admin']) })
    const nav = await screen.findByRole('navigation', { name: 'Staff' })
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Exceptions', 'Bank reviews', 'Applications', 'Metrics', 'Audit ledger', 'Departments', 'Journeys', 'Central schema', 'Onboarding'])
  })

  it('shows who is signed in, with roles and department, and signs out', async () => {
    const { auth } = renderStaff({ route: '/staff', fetchImpl: EMPTY, auth: staffAuth(['officer']) })
    expect(await screen.findByText(/Om Kulkarni \(officer\), SCHOLARSHIP/)).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Sign out' })[0]!)
    expect(auth.signOut).toHaveBeenCalled()
  })

  it('has a page for an unknown staff address', async () => {
    renderStaff({ route: '/staff/nowhere', fetchImpl: EMPTY, auth: staffAuth(['officer']) })
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
