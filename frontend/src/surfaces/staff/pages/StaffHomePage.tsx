import { Link } from 'react-router-dom'
import { useAuth } from '../../../auth/authContext'
import { STAFF_LINKS } from '../nav'
import { useStaffSession } from '../StaffContext'

export function StaffHomePage() {
  const { user } = useAuth()
  const { can, roles, department } = useStaffSession()
  const links = STAFF_LINKS.filter((l) => can(l.roles))

  return (
    <section aria-labelledby="staff-home-h">
      <h1 id="staff-home-h">Staff consoles</h1>
      <p className="lede">
        Signed in as {user?.name} with the {roles.map((r) => r.toLowerCase()).join(' and ')} role
        {roles.length > 1 ? 's' : ''}
        {department ? ` for ${department}` : ''}. You see only what your role may use.
      </p>
      <ul className="grid plain">
        {links.map((l) => (
          <li key={l.to} className="card">
            <h2>
              <Link to={l.to}>{l.label}</Link>
            </h2>
            <p>{l.blurb}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}
