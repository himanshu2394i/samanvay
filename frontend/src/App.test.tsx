import { render, screen } from '@testing-library/react'
import type { User } from 'oidc-client-ts'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import type { OidcManager } from './auth/AuthProvider'
import { fakeJwt } from './test/jwt'

function manager(user: User | null): OidcManager {
  return {
    getUser: vi.fn(async () => user),
    signinRedirect: vi.fn(async () => {}),
    signinCallback: vi.fn(async () => undefined),
    signoutRedirect: vi.fn(async () => {}),
    removeUser: vi.fn(async () => {}),
    events: { addAccessTokenExpired: vi.fn(() => () => {}) },
  } as unknown as OidcManager
}

const userWith = (roles: string[]) =>
  ({
    access_token: fakeJwt({ realm_access: { roles }, department: 'SCHOLARSHIP' }),
    expired: false,
    profile: { sub: 'sub-1', preferred_username: 'dev-officer' },
  }) as unknown as User

// The staff home now pulls live "needs attention" figures; here we only assert routing and
// role gating, so keep the network offline — the dashboard degrades to a quiet line.
beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('offline in unit test')
    }),
  )
})

afterEach(() => {
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

// The whole path a real browser takes: token -> AuthProvider (roles) -> realm-specific app -> route guard.
describe('App wiring', () => {
  it('serves the staff consoles on the staff realm, from the roles in the token', async () => {
    window.history.replaceState(null, '', '/#/staff')
    render(<App manager={manager(userWith(['officer']))} />)
    expect(await screen.findByRole('heading', { name: 'Staff consoles' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Exceptions' }).length).toBeGreaterThan(0) // nav + home card
    expect(screen.queryByRole('link', { name: 'Catalog' })).not.toBeInTheDocument()
  })

  it('lands on the staff home when the IdP returns to the bare page', async () => {
    window.history.replaceState(null, '', '/')
    render(<App manager={manager(userWith(['admin']))} />)
    expect(await screen.findByRole('heading', { name: 'Staff consoles' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/staff')
  })

  it('never shows staff consoles to a citizen-role token, even if it reached the staff app', async () => {
    window.history.replaceState(null, '', '/#/staff/admin/catalog')
    render(<App manager={manager(userWith(['citizen']))} />)
    expect(await screen.findByRole('heading', { name: 'No staff access' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Catalog' })).not.toBeInTheDocument()
  })

  it('sends a hash outside the staff area back to the staff home (there is no citizen area)', async () => {
    window.history.replaceState(null, '', '/#/services')
    render(<App manager={manager(userWith(['officer']))} />)
    expect(await screen.findByRole('heading', { name: 'Staff consoles' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/staff')
  })
})
