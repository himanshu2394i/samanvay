import { Link, useParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { useT } from '../../../i18n'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { humanize } from '../lib/format'

export function ServicePage() {
  const { code = '' } = useParams()
  const api = useCitizenApi()
  const t = useT()
  const data = useAsync(
    async () => {
      const [journey, departments] = await Promise.all([api.getJourney(code), api.listDepartments().catch(() => [])])
      return { journey, departments }
    },
    code,
  )

  if (data.status === 'loading') return <Loading label={t('service.loading')} />
  if (data.status === 'error') return <ErrorNotice error={data.error} onRetry={data.reload} />

  const { journey, departments } = data.data
  const deptName = (c: string) => departments.find((d) => d.code === c)?.name ?? humanize(c)
  const byDept = new Map<string, string[]>()
  for (const [category, dept] of Object.entries(journey.policy.sources)) {
    byDept.set(dept, [...(byDept.get(dept) ?? []), category])
  }

  return (
    <section aria-labelledby="service-h">
      <p>
        <Link to="/services">{t('service.allServices')}</Link>
      </p>
      <h1 id="service-h">{journey.name}</h1>
      <h2>{t('service.whatWeFetch')}</h2>
      <ul className="plain">
        {[...byDept.entries()].map(([dept, categories]) => (
          <li key={dept}>
            <strong>{deptName(dept)}</strong>: {categories.map(humanize).join(', ')}
          </li>
        ))}
      </ul>
      <p className="hint">
        {t('service.consentPurposePrefix')} <code>{journey.policy.purpose}</code>.{' '}
        {t('service.consentSla', { hours: journey.policy.slaHours })}
      </p>
      <Link className="btn primary" to={`/services/${encodeURIComponent(journey.code)}/apply`}>
        {t('service.apply')}
      </Link>
    </section>
  )
}
