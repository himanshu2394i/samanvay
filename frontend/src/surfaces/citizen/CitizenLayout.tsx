import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../../auth/authContext'

const NAV = [
  { to: '/services', label: 'Services' },
  { to: '/applications', label: 'My applications' },
  { to: '/consents', label: 'My consents' },
  { to: '/profile', label: 'My details' },
]

export function CitizenLayout() {
  const { status, user, signIn, signOut } = useAuth()
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="site-header">
        <div className="wrap bar">
          <NavLink to="/" className="brand" end>
            Samanvay <span>Citizen services</span>
          </NavLink>
          {status === 'authenticated' ? (
            <nav aria-label="Main">
              <ul>
                {NAV.map((n) => (
                  <li key={n.to}>
                    <NavLink to={n.to}>{n.label}</NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          ) : null}
          <div className="session">
            {status === 'authenticated' ? (
              <>
                <span className="who">{user?.name}</span>
                <button type="button" className="btn" onClick={() => void signOut()}>
                  Sign out
                </button>
              </>
            ) : (
              <button type="button" className="btn primary" onClick={() => void signIn('/services')}>
                Sign in
              </button>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="wrap" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer wrap">
        <p>
          Samanvay connects you to government departments only with your consent. Development build: departments and
          sign-in providers are mocks.
        </p>
        <p>
          <a href="#/staff">Staff consoles</a> (officers, reviewers and administrators sign in separately)
        </p>
      </footer>
    </>
  )
}
