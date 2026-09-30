import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { useT } from '../../../i18n'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { useCitizen } from '../CitizenContext'
import { formatDate, humanize } from '../lib/format'
import { applicationStatus } from '../lib/status'

export function ApplicationsPage() {
  const api = useCitizenApi()
  const t = useT()
  const { citizenId } = useCitizen()
  const navigate = useNavigate()
  const [ref, setRef] = useState('')

  const data = useAsync(
    async () => {
      const [apps, journeys] = await Promise.all([api.listApplications(citizenId ?? ''), api.listJourneys().catch(() => [])])
      return { apps, names: new Map(journeys.map((j) => [j.code, j.name])) }
    },
    citizenId ?? '',
  )

  function track(e: FormEvent) {
    e.preventDefault()
    const value = ref.trim()
    if (value) void navigate(`/applications/${encodeURIComponent(value)}`)
  }

  return (
    <section aria-labelledby="apps-h">
      <h1 id="apps-h">{t('apps.title')}</h1>
      {data.status === 'loading' ? <Loading label={t('apps.loading')} /> : null}
      {data.status === 'error' ? <ErrorNotice error={data.error} onRetry={data.reload} /> : null}
      {data.status === 'success' ? (
        data.data.apps.length === 0 ? (
          <div className="card narrow">
            <h2>{t('apps.noneHeading')}</h2>
            <p>{t('apps.noneBody')}</p>
            <div className="actions">
              <Link className="btn primary" to="/services">
                {t('apps.browseServices')}
              </Link>
            </div>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">{t('apps.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('apps.colNumber')}</th>
                  <th scope="col">{t('apps.colService')}</th>
                  <th scope="col">{t('apps.colStatus')}</th>
                  <th scope="col">{t('apps.colDecisionDue')}</th>
                </tr>
              </thead>
              <tbody>
                {data.data.apps.map((a) => {
                  const st = applicationStatus(a.status, t)
                  return (
                    <tr key={a.referenceNo}>
                      <td>
                        <Link to={`/applications/${encodeURIComponent(a.referenceNo)}`}>{a.referenceNo}</Link>
                      </td>
                      <td>{data.data.names.get(a.journeyCode) ?? humanize(a.journeyCode)}</td>
                      <td>
                        <Badge tone={st.tone}>{st.short}</Badge>
                      </td>
                      <td>{formatDate(a.slaDueAt) || t('app.na')}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}

      <form className="inline-form" onSubmit={track}>
        <Field label={t('apps.trackLabel')} value={ref} onChange={(e) => setRef(e.target.value)} required />
        <button type="submit" className="btn">
          {t('apps.track')}
        </button>
      </form>
    </section>
  )
}
