import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { loadOidcConfig } from './auth/config'
import { createUserManager, isCallbackUrl } from './auth/oidc'
import { detectRealm, realmFromHash } from './auth/realm'
import { Loading } from './ui/Loading'
import './index.css'

const root = createRoot(document.getElementById('root')!)

let crossingListener = false

async function start() {
  root.render(<Loading label="Starting" />)
  try {
    // The realm is fixed for this page load; moving between the citizen and staff areas
    // (a hash change across the boundary) reloads so the other realm's sign-in is used.
    const realm = detectRealm(window.location, isCallbackUrl(window.location.search))
    // The SPA is the staff/operator console. Citizen journeys live in the static department
    // portals at /, so anyone reaching the SPA as a citizen is sent there — one citizen UI, no duplicate.
    if (realm !== 'staff') {
      window.location.replace('/')
      return
    }
    const cfg = await loadOidcConfig(realm)
    const manager = createUserManager(cfg)
    if (!crossingListener) {
      crossingListener = true
      window.addEventListener('hashchange', () => {
        if (realmFromHash(window.location.hash) !== realm) window.location.reload()
      })
    }
    root.render(
      <StrictMode>
        <App manager={manager} realm={realm} />
      </StrictMode>,
    )
  } catch (e) {
    root.render(
      <main className="wrap">
        <section className="card narrow" role="alert">
          <h1>Samanvay could not start</h1>
          <p>{e instanceof Error ? e.message : 'Unknown error.'}</p>
          <button type="button" className="btn" onClick={() => void start()}>
            Try again
          </button>
        </section>
      </main>,
    )
  }
}

void start()
