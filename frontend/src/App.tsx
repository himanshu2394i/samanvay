import { HashRouter } from 'react-router-dom'
import { ApiProvider } from './api/ApiProvider'
import { AuthProvider, type OidcManager } from './auth/AuthProvider'
import { CitizenApp } from './surfaces/citizen/CitizenApp'

/**
 * Hash routing: the built files are served as plain static resources (Spring, no SPA
 * fallback), so deep links must not need server-side rewrites. It also keeps the OIDC
 * redirect URI a single fixed page.
 */
export function App({ manager }: { manager: OidcManager }) {
  return (
    <AuthProvider manager={manager}>
      <ApiProvider>
        <HashRouter>
          <CitizenApp />
        </HashRouter>
      </ApiProvider>
    </AuthProvider>
  )
}
