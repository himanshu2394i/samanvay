import { Link } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { useT } from '../../../i18n'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { humanize } from '../lib/format'

export function ServicesPage() {
  const api = useCitizenApi()
  const t = useT()
  const journeys = useAsync(() => api.listJourneys(), "journeys")

  return (
    <section aria-labelledby="services-h">
      <h1 id="services-h">{t('services.title')}</h1>
      <p className="lede">{t('services.lede')}</p>
      {journeys.status === 'loading' ? <Loading label={t('services.loading')} /> : null}
      {journeys.status === 'error' ? <ErrorNotice error={journeys.error} onRetry={journeys.reload} /> : null}
      {journeys.status === 'success' ? (
        journeys.data.filter((j) => j.status === 'PUBLISHED').length === 0 ? (
          <p>{t('services.none')}</p>
        ) : (
          <ul className="grid">
            {journeys.data
              .filter((j) => j.status === 'PUBLISHED')
              .map((j) => (
                <li key={j.code} className="card">
                  <h2>
                    <Link to={`/services/${encodeURIComponent(j.code)}`}>{j.name}</Link>
                  </h2>
                  <p className="hint">{t('services.recordsNeeded', { categories: j.requiredCategories.map(humanize).join(', ') })}</p>
                  <p className="hint">{t('services.decisionTarget', { hours: j.policy.slaHours })}</p>
                  <Link className="btn" to={`/services/${encodeURIComponent(j.code)}`}>
                    {t('services.viewDetails')}
                  </Link>
                </li>
              ))}
          </ul>
        )
      ) : null}
    </section>
  )
}
