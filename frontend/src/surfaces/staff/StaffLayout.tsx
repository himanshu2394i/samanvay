import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../../auth/authContext'
import { hasAnyRole, staffRolesOf } from '../../auth/roles'
import { STAFF_LINKS } from './nav'

export function StaffLayout() {
  const { status, user, signIn, signOut } = useAuth()
  const roles = staffRolesOf(user?.roles ?? [])
  const links = STAFF_LINKS.filter((l) => hasAnyRole(roles, l.roles))

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="site-header staff">
        <div className="wrap wide identity">
          <span className="eyebrow">Government of Maharashtra</span>
          <span className="demo-tag">Demo build</span>
        </div>
        <div className="wrap wide bar">
          <NavLink to="/staff" className="brand" end>
            Samanvay <span>Staff consoles</span>
          </NavLink>
          {status === 'authenticated' && links.length > 0 ? (
            <nav aria-label="Staff">
              <ul>
                {links.map((l) => (
                  <li key={l.to}>
                    <NavLink to={l.to}>{l.label}</NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          ) : null}
          <div className="session">
            {status === 'authenticated' ? (
              <>
                <span className="who">
                  {user?.name}
                  {roles.length ? ` (${roles.map((r) => r.toLowerCase()).join(', ')})` : ''}
                  {user?.department ? `, ${user.department}` : ''}
                </span>
                <button type="button" className="btn" onClick={() => void signOut()}>
                  Sign out
                </button>
              </>
            ) : (
              <button type="button" className="btn primary" onClick={() => void signIn('/staff')}>
                Staff sign in
              </button>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="wrap wide" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer wrap wide">
        <p>
          Staff consoles. Everything here is authorised again by the API from your token on every request.
        </p>
      </footer>
    </>
  )
}
