import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { User } from 'oidc-client-ts'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, type OidcManager } from './AuthProvider'
import { useAuth } from './authContext'
import { RequireAuth } from './RequireAuth'
import { currentRedirectUri, isCallbackUrl } from './oidc'

function fakeUser(over: Partial<{ expired: boolean; state: unknown; token: string }> = {}): User {
  return {
    access_token: over.token ?? 'access-1',
    expired: over.expired ?? false,
    state: over.state,
    profile: { sub: 'sub-1', preferred_username: 'dev-citizen' },
  } as unknown as User
}

function fakeManager(over: Partial<Record<keyof OidcManager, unknown>> = {}) {
  const expiredHandlers: (() => void)[] = []
  const manager = {
    getUser: vi.fn(async () => null as User | null),
    signinRedirect: vi.fn(async () => {}),
    signinCallback: vi.fn(async () => undefined as User | undefined),
    signoutRedirect: vi.fn(async () => {}),
    removeUser: vi.fn(async () => {}),
    events: {
      addAccessTokenExpired: vi.fn((cb: () => void) => {
        expiredHandlers.push(cb)
        return () => {}
      }),
    },
    ...over,
  }
  return { manager: manager as unknown as OidcManager & typeof manager, fireExpired: () => expiredHandlers.forEach((h) => h()) }
}

function Probe() {
  const { status, user, notice, getAccessToken } = useAuth()
  return (
    <div>
      <p>status:{status}</p>
      <p>user:{user?.name ?? 'none'}</p>
      <p>notice:{notice ?? 'none'}</p>
      <button onClick={() => void getAccessToken().then((t) => (document.title = String(t)))}>token</button>
    </div>
  )
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('AuthProvider', () => {
  it('is signed out when there is no stored session', async () => {
    const { manager } = fakeManager()
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:unauthenticated')).toBeInTheDocument()
    expect(manager.signinCallback).not.toHaveBeenCalled()
  })

  it('restores a live session and hands out its access token', async () => {
    const { manager } = fakeManager({ getUser: vi.fn(async () => fakeUser()) })
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:authenticated')).toBeInTheDocument()
    expect(screen.getByText('user:dev-citizen')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'token' }))
    await waitFor(() => expect(document.title).toBe('access-1'))
  })

  it('treats an expired stored session as signed out and never returns its token', async () => {
    const { manager } = fakeManager({ getUser: vi.fn(async () => fakeUser({ expired: true })) })
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:unauthenticated')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'token' }))
    await waitFor(() => expect(document.title).toBe('null'))
  })

  it('completes the redirect back from the IdP and returns to the saved route', async () => {
    window.history.replaceState(null, '', '/?code=abc&state=xyz')
    const user = fakeUser({ state: { returnTo: '/services/X/apply' } })
    const { manager } = fakeManager({
      signinCallback: vi.fn(async () => user),
      getUser: vi.fn(async () => user),
    })
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:authenticated')).toBeInTheDocument()
    expect(manager.signinCallback).toHaveBeenCalledOnce()
    expect(window.location.search).toBe('')
    expect(window.location.hash).toBe('#/services/X/apply')
  })

  it('shows a notice and stays signed out when the callback fails', async () => {
    window.history.replaceState(null, '', '/?error=access_denied&state=xyz')
    const { manager } = fakeManager({
      signinCallback: vi.fn(async () => {
        throw new Error('access_denied')
      }),
    })
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('status:unauthenticated')).toBeInTheDocument()
    expect(screen.getByText(/notice:Sign-in could not be completed/)).toBeInTheDocument()
    expect(window.location.search).toBe('')
  })

  it('drops the session with a notice when the access token expires', async () => {
    const { manager, fireExpired } = fakeManager({ getUser: vi.fn(async () => fakeUser()) })
    render(
      <AuthProvider manager={manager}>
        <Probe />
      </AuthProvider>,
    )
    await screen.findByText('status:authenticated')
    fireExpired()
    expect(await screen.findByText('status:unauthenticated')).toBeInTheDocument()
    expect(screen.getByText(/notice:Your session has expired/)).toBeInTheDocument()
    expect(manager.removeUser).toHaveBeenCalled()
  })
})

describe('RequireAuth', () => {
  function Guarded({ manager, at = '/services?x=1' }: { manager: OidcManager; at?: string }) {
    return (
      <AuthProvider manager={manager}>
        <MemoryRouter initialEntries={[at]}>
          <RequireAuth>
            <p>secret page</p>
          </RequireAuth>
        </MemoryRouter>
      </AuthProvider>
    )
  }

  it('blocks a signed-out visitor and signs in with the page to come back to', async () => {
    const { manager } = fakeManager()
    render(<Guarded manager={manager} />)

    expect(await screen.findByRole('heading', { name: 'Sign in to continue' })).toBeInTheDocument()
    expect(screen.queryByText('secret page')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/services?x=1' } })
  })

  it('renders the page for a signed-in citizen', async () => {
    const { manager } = fakeManager({ getUser: vi.fn(async () => fakeUser()) })
    render(<Guarded manager={manager} />)
    expect(await screen.findByText('secret page')).toBeInTheDocument()
  })
})

describe('oidc helpers', () => {
  it('recognises a callback URL only with state plus code or error', () => {
    expect(isCallbackUrl('?code=1&state=2')).toBe(true)
    expect(isCallbackUrl('?error=access_denied&state=2')).toBe(true)
    expect(isCallbackUrl('?code=1')).toBe(false)
    expect(isCallbackUrl('')).toBe(false)
  })

  it('uses this page (no query/hash) as the redirect URI', () => {
    expect(currentRedirectUri({ origin: 'http://localhost:8080', pathname: '/app/index.html' })).toBe(
      'http://localhost:8080/app/index.html',
    )
  })
})
