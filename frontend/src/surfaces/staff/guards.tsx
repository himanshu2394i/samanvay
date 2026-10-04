import { useMemo } from 'react'
import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '../../auth/authContext'
import { RequireAuth } from '../../auth/RequireAuth'
import { staffRolesOf, type StaffRole } from '../../auth/roles'
import { makeStaffSession, StaffSessionContext, useStaffSession } from './StaffContext'

/**
 * Everything behind sign-in on the staff realm, and only for a token that carries at least
 * one staff role (officer, reviewer, admin). A signed-in account with none of them (or a
 * token from anywhere else) sees an explanation, never a staff page. This is a
 * presentation gate; the API enforces the same roles on every call.
 */
export function RequireStaff() {
  return (
    <RequireAuth
      audience="use the staff consoles"
      hint="Staff sign in with a passkey, or with a password and an authenticator code. Keycloak hosts that sign-in; this app never sees your password."
    >
      <StaffRoles />
    </RequireAuth>
  )
}

function StaffRoles() {
  const { user, signOut } = useAuth()
  const roles = useMemo(() => staffRolesOf(user?.roles ?? []), [user])
  const session = useMemo(() => makeStaffSession(roles, user?.department ?? null), [roles, user])

  if (roles.length === 0) {
    return (
      <section className="card narrow" role="alert" aria-labelledby="no-staff-role">
        <h1 id="no-staff-role">No staff access</h1>
        <p>This account does not have a staff role (officer, reviewer or admin), so the staff consoles are not available to it.</p>
        <div className="actions">
          <button type="button" className="btn" onClick={() => void signOut()}>
            Sign out
          </button>
        </div>
      </section>
    )
  }
  return (
    <StaffSessionContext.Provider value={session}>
      <Outlet />
    </StaffSessionContext.Provider>
  )
}

/** A route family for specific staff roles. Anyone else gets a plain "not for your role" page. */
export function RequireRole({ allow }: { allow: readonly StaffRole[] }) {
  const { can, roles } = useStaffSession()
  if (can(allow)) return <Outlet />
  return (
    <section className="card narrow" role="alert" aria-labelledby="wrong-role">
      <h1 id="wrong-role">Not available for your role</h1>
      <p>
        This page is for {allow.map((r) => r.toLowerCase()).join(' or ')} staff. You are signed in as{' '}
        {roles.map((r) => r.toLowerCase()).join(', ')}.
      </p>
      <p>
        <Link to="/staff">Back to your consoles</Link>
      </p>
    </section>
  )
}
