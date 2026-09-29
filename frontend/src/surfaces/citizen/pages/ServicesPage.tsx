import { Link } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { humanize } from '../lib/format'

export function ServicesPage() {
  const api = useCitizenApi()
  const journeys = useAsync(() => api.listJourneys(), "journeys")

  return (
    <section aria-labelledby="services-h">
      <h1 id="services-h">Services</h1>
      <p className="lede">Choose a service to see what it needs and to apply.</p>
      {journeys.status === 'loading' ? <Loading label="Loading services" /> : null}
      {journeys.status === 'error' ? <ErrorNotice error={journeys.error} onRetry={journeys.reload} /> : null}
      {journeys.status === 'success' ? (
        journeys.data.filter((j) => j.status === 'PUBLISHED').length === 0 ? (
          <p>No services are open for applications right now.</p>
        ) : (
          <ul className="grid">
            {journeys.data
              .filter((j) => j.status === 'PUBLISHED')
              .map((j) => (
                <li key={j.code} className="card">
                  <h2>
                    <Link to={`/services/${encodeURIComponent(j.code)}`}>{j.name}</Link>
                  </h2>
                  <p className="hint">Records needed: {j.requiredCategories.map(humanize).join(', ')}.</p>
                  <p className="hint">Decision target: within {j.policy.slaHours} hours.</p>
                  <Link className="btn" to={`/services/${encodeURIComponent(j.code)}`}>
                    View details
                  </Link>
                </li>
              ))}
          </ul>
        )
      ) : null}
    </section>
  )
}
