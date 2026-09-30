import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { User } from 'oidc-client-ts'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fakeJwt } from '../test/jwt'
import { AuthProvider, type OidcManager } from './AuthProvider'
import { useAuth } from './authContext'
import { detectRealm } from './realm'

function staffUser(roles: string[], department?: string): User {
  return {
    access_token: fakeJwt({ realm_access: { roles }, ...(department ? { department } : {}) }),
    expired: false,
    profile: { sub: 'sub-staff', preferred_username: 'dev-officer' },
  } as unknown as User
}

function manager(over: Partial<Record<keyof OidcManager, unknown>> = {}) {
  return {
    getUser: vi.fn(async () => null as User | null),
    signinRedirect: vi.fn(async () => {}),
    signinCallback: vi.fn(async () => undefined as User | undefined),
    signoutRedirect: vi.fn(async () => {}),
    removeUser: vi.fn(async () => {}),
    events: { addAccessTokenExpired: vi.fn(() => () => {}) },
    ...over,
  } as unknown as OidcManager & { signinRedirect: ReturnType<typeof vi.fn>; signinCallback: ReturnType<typeof vi.fn> }
}

function Probe() {
  const { status, user, signIn, demoSignIn } = useAuth()
  return (
    <div>
      <p>status:{status}</p>
      <p>roles:{user?.roles.join(',') ?? 'none'}</p>
      <p>department:{user?.department ?? 'none'}</p>
      <button onClick={() => void signIn('/staff/ops/metrics')}>sign in</button>
      <button onClick={() => void demoSignIn('admin')}>demo admin</button>
    </div>
  )
}

afterEach(() => window.history.replaceState(null, '', '/'))

describe('AuthProvider with a staff session', () => {
  it('exposes the roles and department carried by the access token', async () => {
    const m = manager({ getUser: vi.fn(async () => staffUser(['officer', 'reviewer'], 'SCHOLARSHIP')) })
    render(
      <AuthProvider manager={m} realm="staff">
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:authenticated')).toBeInTheDocument()
    expect(screen.getByText('roles:OFFICER,REVIEWER')).toBeInTheDocument()
    expect(screen.getByText('department:SCHOLARSHIP')).toBeInTheDocument()
  })

  it('remembers the staff realm across the IdP redirect, then forgets it once the callback is handled', async () => {
    const m = manager()
    const view = render(
      <AuthProvider manager={m} realm="staff">
        <Probe />
      </AuthProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'sign in' }))
    expect(m.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/staff/ops/metrics' } })
    // the redirect lands on a bare URL: the marker is how main.tsx knows which realm to use
    expect(detectRealm({ hash: '' }, true)).toBe('staff')
    view.unmount()

    window.history.replaceState(null, '', '/?code=abc&state=xyz')
    const user = { ...staffUser(['admin']), state: { returnTo: '/staff/admin/catalog' } } as unknown as User
    const cb = manager({ signinCallback: vi.fn(async () => user), getUser: vi.fn(async () => user) })
    render(
      <AuthProvider manager={cb} realm="staff">
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:authenticated')).toBeInTheDocument()
    expect(window.location.hash).toBe('#/staff/admin/catalog')
    expect(detectRealm({ hash: '' }, true)).toBe('citizen')
  })

  it('demo sign-in mints a session from the API and stores it, with no Keycloak redirect', async () => {
    const token = fakeJwt({
      sub: 'demo-admin',
      name: 'Demo Admin',
      preferred_username: 'demo-admin',
      iss: 'samanvay-demo-staff',
      realm_access: { roles: ['admin'] },
      exp: Math.floor(Date.now() / 1000) + 3600,
    })
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ access_token: token, token_type: 'Bearer' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const stored: User[] = []
    const m = manager({
      storeUser: vi.fn(async (u: User) => void stored.push(u)),
      getUser: vi.fn(async () => stored[stored.length - 1] ?? null),
    })

    render(
      <AuthProvider manager={m} realm="staff" devSignIn>
        <Probe />
      </AuthProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'demo admin' }))

    expect(fetchMock).toHaveBeenCalledWith('/ui/demo-signin', expect.objectContaining({ method: 'POST' }))
    expect(await screen.findByText('status:authenticated')).toBeInTheDocument()
    expect(screen.getByText('roles:ADMIN')).toBeInTheDocument()
    expect(stored).toHaveLength(1)
    expect(m.signinRedirect).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
