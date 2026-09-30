import { HashRouter } from 'react-router-dom'
import { ApiProvider } from './api/ApiProvider'
import { AuthProvider, type OidcManager } from './auth/AuthProvider'
import type { RealmKey } from './auth/config'
import { CitizenApp } from './surfaces/citizen/CitizenApp'
import { StaffApp } from './surfaces/staff/StaffApp'

/**
 * Hash routing: the built files are served as plain static resources (Spring, no SPA
 * fallback), so deep links must not need server-side rewrites. It also keeps the OIDC
 * redirect URI a single fixed page.
 *
 * One page load serves ONE realm (see auth/realm.ts): the citizen surface on the citizen
 * realm, the staff surfaces (#/staff/...) on the staff realm. `manager` is the
 * UserManager of that realm.
 */
export function App({
  manager,
  realm = 'citizen',
  devSignIn = false,
}: {
  manager: OidcManager
  realm?: RealmKey
  devSignIn?: boolean
}) {
  return (
    <AuthProvider manager={manager} realm={realm} devSignIn={devSignIn}>
      <ApiProvider>
        <HashRouter>{realm === 'staff' ? <StaffApp /> : <CitizenApp />}</HashRouter>
      </ApiProvider>
    </AuthProvider>
  )
}
