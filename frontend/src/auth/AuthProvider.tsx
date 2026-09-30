import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { User } from 'oidc-client-ts'
import { AuthContext, type AuthContextValue, type AuthStatus, type AuthUser } from './authContext'
import type { RealmKey } from './config'
import { isCallbackUrl } from './oidc'
import { clearSigninRealm, rememberSigninRealm } from './realm'
import { decodeJwtPayload, departmentFromToken, rolesFromToken } from './roles'
import { Loading } from '../ui/Loading'

/** The slice of oidc-client-ts's UserManager the app uses (so tests can supply a fake). */
export interface OidcManager {
  getUser(): Promise<User | null>
  signinRedirect(args?: { state?: unknown }): Promise<void>
  signinCallback(url?: string): Promise<User | undefined | void>
  signoutRedirect(): Promise<void>
  removeUser(): Promise<void>
  /** Stores a User in the manager's session store (used by the demo sign-in). */
  storeUser(user: User): Promise<void>
  events: { addAccessTokenExpired(cb: () => void): () => void }
}

interface Props {
  manager: OidcManager
  /** Which realm this manager signs in to; remembered across the IdP redirect. Default citizen. */
  realm?: RealmKey
  /** Dev/demo build only: expose the one-click demo sign-in. Always false in prod. */
  devSignIn?: boolean
  children: ReactNode
}

interface ReturnState {
  returnTo?: string
}

function toAuthUser(user: User): AuthUser {
  const p = user.profile as Record<string, unknown>
  const pick = (k: string) => (typeof p[k] === 'string' && p[k] ? (p[k] as string) : null)
  return {
    sub: user.profile.sub,
    name: pick('name') ?? pick('preferred_username') ?? pick('email') ?? user.profile.sub,
    roles: rolesFromToken(user.access_token),
    department: departmentFromToken(user.access_token),
  }
}

export function AuthProvider({ manager, realm = 'citizen', devSignIn = false, children }: Props) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [user, setUser] = useState<AuthUser | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const started = useRef(false)

  const expireSession = useCallback(
    (message = 'Your session has ended. Sign in again to continue.') => {
      void manager.removeUser().catch(() => {})
      setUser(null)
      setNotice(message)
      setStatus('unauthenticated')
    },
    [manager],
  )

  // Boot once: finish an in-flight sign-in redirect, or restore the session from storage.
  useEffect(() => {
    if (started.current) return
    started.current = true
    void (async () => {
      try {
        if (isCallbackUrl(window.location.search)) {
          const signedIn = await manager.signinCallback()
          clearSigninRealm()
          const returnTo = ((signedIn && (signedIn as User).state) as ReturnState | undefined)?.returnTo
          // Drop ?code=&state= and land on the page the citizen was heading to.
          const { origin, pathname } = window.location
          window.history.replaceState(null, '', `${origin}${pathname}${returnTo ? `#${returnTo}` : ''}`)
        }
        const existing = await manager.getUser()
        if (existing && !existing.expired) {
          setUser(toAuthUser(existing))
          setStatus('authenticated')
        } else {
          setStatus('unauthenticated')
        }
      } catch {
        clearSigninRealm()
        const { origin, pathname } = window.location
        window.history.replaceState(null, '', origin + pathname)
        setNotice('Sign-in could not be completed. Please try again.')
        setStatus('unauthenticated')
      }
    })()
  }, [manager])

  // Access tokens are short-lived and there is no refresh token: when one expires, say so.
  useEffect(() => {
    return manager.events.addAccessTokenExpired(() => expireSession('Your session has expired. Sign in again to continue.'))
  }, [manager, expireSession])

  const signIn = useCallback(
    async (returnTo?: string) => {
      setNotice(null)
      rememberSigninRealm(realm)
      await manager.signinRedirect({ state: { returnTo } satisfies ReturnState })
    },
    [manager, realm],
  )

  // Demo/dev only: swap the Keycloak login for a demo token minted by the API (POST /ui/demo-signin,
  // which exists only in the demo profile). We store it as an oidc User so every other path — session
  // restore, getAccessToken, sign-out — works unchanged.
  const demoSignIn = useCallback(
    async (role: string) => {
      setNotice(null)
      const res = await fetch('/ui/demo-signin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role }),
      }).catch(() => null)
      if (!res || !res.ok) {
        setNotice('Demo sign-in is not available in this build.')
        return
      }
      const { access_token: token } = (await res.json()) as { access_token: string }
      const claims = decodeJwtPayload(token) as Record<string, unknown>
      const str = (k: string) => (typeof claims[k] === 'string' ? (claims[k] as string) : undefined)
      const demo = new User({
        access_token: token,
        token_type: 'Bearer',
        scope: 'openid',
        profile: {
          sub: str('sub') ?? 'demo',
          name: str('name'),
          preferred_username: str('preferred_username'),
          iss: str('iss') ?? '',
          aud: '',
        },
        expires_at: typeof claims.exp === 'number' ? (claims.exp as number) : undefined,
      } as ConstructorParameters<typeof User>[0])
      await manager.storeUser(demo)
      setUser(toAuthUser(demo))
      setStatus('authenticated')
    },
    [manager],
  )

  const signOut = useCallback(async () => {
    // A demo session has no Keycloak session to end — just drop it locally (no IdP redirect).
    const current = await manager.getUser().catch(() => null)
    const iss = current?.profile?.iss
    const isDemo = typeof iss === 'string' && iss.startsWith('samanvay-demo')
    setUser(null)
    setStatus('unauthenticated')
    if (isDemo) {
      await manager.removeUser().catch(() => {})
      return
    }
    try {
      await manager.signoutRedirect()
    } catch {
      await manager.removeUser()
    }
  }, [manager])

  const getAccessToken = useCallback(async () => {
    const u = await manager.getUser()
    return u && !u.expired ? u.access_token : null
  }, [manager])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, notice, signIn, signOut, getAccessToken, expireSession, realm, devSignIn, demoSignIn }),
    [status, user, notice, signIn, signOut, getAccessToken, expireSession, realm, devSignIn, demoSignIn],
  )

  if (status === 'loading') return <Loading label="Signing you in" />
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
