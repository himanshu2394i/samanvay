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
  const { status, user, signIn } = useAuth()
  return (
    <div>
      <p>status:{status}</p>
      <p>roles:{user?.roles.join(',') ?? 'none'}</p>
      <p>department:{user?.department ?? 'none'}</p>
      <button onClick={() => void signIn('/staff/ops/metrics')}>sign in</button>
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
})
