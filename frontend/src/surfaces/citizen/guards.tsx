import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { RequireAuth } from '../../auth/RequireAuth'
import { CitizenProvider, useCitizen } from './CitizenContext'

/** Everything behind sign-in. Also provides the citizen record id to its routes. */
export function ProtectedArea() {
  return (
    <RequireAuth>
      <CitizenProvider>
        <Outlet />
      </CitizenProvider>
    </RequireAuth>
  )
}

/** Routes that act on the citizen's own record need it registered first (the details form). */
export function RequireProfile() {
  const { citizenId } = useCitizen()
  const location = useLocation()
  if (!citizenId) {
    return <Navigate to="/profile" replace state={{ from: location.pathname + location.search }} />
  }
  return <Outlet />
}
