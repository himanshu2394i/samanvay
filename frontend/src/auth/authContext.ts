import { createContext, useContext } from 'react'
import type { RealmKey } from './config'

export interface AuthUser {
  /** Token subject: the identity the API binds a citizen record to. */
  sub: string
  /** Display name: name, preferred_username or email, whichever the token carries. */
  name: string
  /**
   * Realm roles from the access token, upper-cased (OFFICER, REVIEWER, ADMIN, CITIZEN).
   * For deciding what to show only: the API re-checks every call against the verified token.
   */
  roles: string[]
  /** Staff only: the catalog department claim of the token, if any. */
  department: string | null
}

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated'

export interface AuthContextValue {
  status: AuthStatus
  user: AuthUser | null
  /** Why the session ended or sign-in failed, when there is something to tell the user. */
  notice: string | null
  /** returnTo is an in-app route (e.g. /services/X/apply) to come back to after sign-in. */
  signIn: (returnTo?: string) => Promise<void>
  signOut: () => Promise<void>
  /** The current bearer token, or null when signed out / expired. */
  getAccessToken: () => Promise<string | null>
  /** Drops the local session (e.g. after the API answered 401). */
  expireSession: (notice?: string) => void
  /** Which realm this page signs in to (staff or citizen); picks the demo roles offered. */
  realm: RealmKey
  /** True in the dev/demo build only: show the one-click demo sign-in. Always false in prod. */
  devSignIn: boolean
  /** Demo/dev only: sign in as a demo role (admin/officer/reviewer/citizen) with no password. */
  demoSignIn: (role: string) => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
