import { HashRouter } from 'react-router-dom'
import { ApiProvider } from './api/ApiProvider'
import { AuthProvider, type OidcManager } from './auth/AuthProvider'
import { StaffApp } from './surfaces/staff/StaffApp'

/**
 * Samanvay is for the Samanvay team and officers: this app is the staff console and signs in on the staff realm only. Citizens never
 * come here; they use their own department's portal.
 *
 * Hash routing: the built files are served as plain static resources (Spring, no SPA fallback), so deep links must not need
 * server-side rewrites. It also keeps the OIDC redirect URI a single fixed page.
 */
export function App({ manager }: { manager: OidcManager }) {
  return (
    <AuthProvider manager={manager} realm="staff">
      <ApiProvider>
        <HashRouter>
          <StaffApp />
        </HashRouter>
      </ApiProvider>
    </AuthProvider>
  )
}
