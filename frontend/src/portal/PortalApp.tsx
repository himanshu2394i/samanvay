import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom'
import { createPortalApi, type Branding, type Session } from './api'
import { PortalContext, usePortal } from './context'
import { ApplicationDetailPage } from './pages/ApplicationDetail'
import { ApplicationsPage } from './pages/Applications'
import { JourneyPage } from './pages/Journey'
import { NotFoundPage } from './pages/NotFound'
import { ServicesPage } from './pages/Services'
import { SignInPage } from './pages/SignIn'
import { Loading } from './ui'

interface Props {
  /** For tests. */
  fetchImpl?: typeof fetch
  /** For tests: how the browser leaves for another address. */
  goTo?: (url: string) => void
}

/** Show this department's accent. The server sends the three colours; CSS picks light or dark. */
function applyBranding(b: Branding) {
  const root = document.documentElement
  root.style.setProperty('--accent', b.accent)
  root.style.setProperty('--accent-dark', b.accentDark)
  root.style.setProperty('--on-accent-dark', b.onAccentDark)
  document.title = `${b.name}: citizen services`
}

export function PortalApp({ fetchImpl, goTo }: Props) {
  const [session, setSession] = useState<Session | null | undefined>(undefined)
  const [branding, setBranding] = useState<Branding | null>(null)

  const api = useMemo(
    () => createPortalApi(fetchImpl ?? ((input, init) => window.fetch(input, init)), () => setSession(null)),
    [fetchImpl],
  )

  useEffect(() => {
    let live = true
    api.config().then(
      (b) => {
        if (!live) return
        applyBranding(b)
        setBranding(b)
      },
      () => {}, // neutral look and a generic title are fine
    )
    api.me().then(
      (s) => live && setSession(s),
      () => live && setSession(null),
    )
    return () => {
      live = false
    }
  }, [api])

  const value = useMemo(
    () => ({ api, branding, session, setSession, goTo: goTo ?? ((url: string) => window.location.assign(url)) }),
    [api, branding, session, goTo],
  )

  return (
    <PortalContext.Provider value={value}>
      <a className="skip" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus() }}>
        Skip to the main content
      </a>
      <Routes>
        <Route path="/sign-in" element={<Frame><SignInPage /></Frame>} />
        <Route element={<Authenticated />}>
          <Route index element={<ServicesPage />} />
          <Route path="journeys/:code" element={<JourneyPage />} />
          <Route path="applications" element={<ApplicationsPage />} />
          <Route path="applications/:ref" element={<ApplicationDetailPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </PortalContext.Provider>
  )
}

function Authenticated() {
  const { session } = usePortal()
  if (session === undefined) {
    return (
      <Frame>
        <Loading label="Checking your sign-in" />
      </Frame>
    )
  }
  if (session === null) return <Navigate to="/sign-in" replace />
  return (
    <Frame nav>
      <Outlet />
    </Frame>
  )
}

function Frame({ nav = false, children }: { nav?: boolean; children: ReactNode }) {
  const { branding, session, api, setSession } = usePortal()
  const navigate = useNavigate()

  async function signOut() {
    try {
      await api.signOut()
    } catch {
      // the cookie may already be gone; leave anyway
    }
    setSession(null)
    navigate('/sign-in')
  }

  return (
    <>
      <header className="top">
        <div className="top-inner">
          <div className="brand">
            <span className="mark" aria-hidden="true">
              {branding?.initial ?? ''}
            </span>
            <span className="brand-text">
              <span className="brand-name">{branding?.name ?? 'Citizen services'}</span>
              <span className="brand-sub">Citizen services</span>
            </span>
          </div>
          {nav && session ? (
            <nav className="nav" aria-label="Main">
              <NavLink to="/" end>
                Services
              </NavLink>
              <NavLink to="/applications">My applications</NavLink>
              <span className="who">
                <span className="who-name">{session.name}</span>
                <button type="button" className="btn link" onClick={() => void signOut()}>
                  Sign out
                </button>
              </span>
            </nav>
          ) : null}
        </div>
      </header>
      <main id="main" tabIndex={-1} className="page">
        {children}
      </main>
    </>
  )
}
