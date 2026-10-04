import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { loadOidcConfig } from './auth/config'
import { createUserManager } from './auth/oidc'
import { Loading } from './ui/Loading'
import './index.css'

const root = createRoot(document.getElementById('root')!)

async function start() {
  root.render(<Loading label="Starting" />)
  try {
    // This app is the staff console only (Samanvay has no citizen sign in): one realm, nothing to detect.
    const cfg = await loadOidcConfig('staff')
    const manager = createUserManager(cfg)
    root.render(
      <StrictMode>
        <App manager={manager} />
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
