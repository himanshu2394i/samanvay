import { Link, useParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { humanize } from '../lib/format'

export function ServicePage() {
  const { code = '' } = useParams()
  const api = useCitizenApi()
  const data = useAsync(
    async () => {
      const [journey, departments] = await Promise.all([api.getJourney(code), api.listDepartments().catch(() => [])])
      return { journey, departments }
    },
    code,
  )

  if (data.status === 'loading') return <Loading label="Loading service" />
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
        <Link to="/services">All services</Link>
      </p>
      <h1 id="service-h">{journey.name}</h1>
      <h2>What we will fetch, and from whom</h2>
      <ul className="plain">
        {[...byDept.entries()].map(([dept, categories]) => (
          <li key={dept}>
            <strong>{deptName(dept)}</strong>: {categories.map(humanize).join(', ')}
          </li>
        ))}
      </ul>
      <p className="hint">
        You will be asked for consent for this purpose: <code>{journey.policy.purpose}</code>. Your application is
        targeted for a decision within {journey.policy.slaHours} hours.
      </p>
      <Link className="btn primary" to={`/services/${encodeURIComponent(journey.code)}/apply`}>
        Apply for this service
      </Link>
    </section>
  )
}
