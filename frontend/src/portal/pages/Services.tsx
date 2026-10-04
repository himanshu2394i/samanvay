import { Link } from 'react-router-dom'
import { usePortal } from '../context'
import { useAsync } from '../../ui/useAsync'
import { ErrorNotice, Loading } from '../ui'

export function ServicesPage() {
  const { api, session } = usePortal()
  const list = useAsync(() => api.journeys(), 'journeys')

  return (
    <>
      <h1>Services</h1>
      <p className="lead">Hello {session?.name}. Choose a service to apply for.</p>

      {list.status === 'loading' ? <Loading label="Loading services" /> : null}
      {list.status === 'error' ? <ErrorNotice error={list.error} onRetry={list.reload} /> : null}
      {list.status === 'success' && list.data.length === 0 ? (
        <div className="empty">
          <p>There are no services here yet. Please check again later.</p>
        </div>
      ) : null}
      {list.status === 'success' && list.data.length > 0 ? (
        <ul className="cards">
          {list.data.map((j) => (
            <li className="card service" key={j.code}>
              <h2>{j.name}</h2>
              <p>{j.description}</p>
              <Link className="btn" to={`/journeys/${encodeURIComponent(j.code)}`} aria-label={`Start ${j.name}`}>
                Start
              </Link>
            </li>
          ))}
        </ul>
      ) : null}

      <p className="more">
        <Link to="/applications">My applications</Link>
      </p>
    </>
  )
}
