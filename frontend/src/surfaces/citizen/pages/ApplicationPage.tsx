import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { ApiError } from '../../../api/client'
import { useT } from '../../../i18n'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { StatusStepper } from '../../../ui/StatusStepper'
import { useAsync } from '../../../ui/useAsync'
import { formatDate, humanize } from '../lib/format'
import { applicationStatus, stepStatus } from '../lib/status'

/** How often an application that is still in progress is re-checked. */
export const REFRESH_MS = 8000

export function ApplicationPage() {
  const { ref = '' } = useParams()
  const api = useCitizenApi()
  const t = useT()
  const data = useAsync(
    async () => {
      const [application, steps, records, disbursement] = await Promise.all([
        api.getApplication(ref),
        api.getApplicationSteps(ref),
        // A preview that fails must not hide the application itself.
        api.getIssuedRecords(ref).catch(() => null),
        // Absent (204 -> undefined) until sanctioned; a failure just hides the panel.
        api.getDisbursement(ref).catch(() => undefined),
      ])
      return { application, steps, records, disbursement }
    },
    ref,
  )
  const { reload } = data
  const final = data.status === 'success' && applicationStatus(data.data.application.status).final

  // Keep an in-progress application fresh; stop once it reaches a final state.
  const watching = data.status === 'success' && !final
  useEffect(() => {
    if (!watching) return
    const t = setInterval(reload, REFRESH_MS)
    return () => clearInterval(t)
  }, [watching, reload])

  if (data.status === 'loading') return <Loading label={t('app.loading')} />
  if (data.status === 'error') {
    const notFound = data.error instanceof ApiError && data.error.status === 404
    return (
      <section className="card narrow">
        <h1>{t('app.notFoundTitle')}</h1>
        {notFound ? (
          <p>
            {t('app.notFoundPrefix')} <code>{ref}</code> {t('app.notFoundSuffix')}
          </p>
        ) : (
          <ErrorNotice error={data.error} onRetry={data.reload} />
        )}
        <Link className="btn" to="/applications">
          {t('app.myApplications')}
        </Link>
      </section>
    )
  }

  const { application, steps, records, disbursement } = data.data
  const st = applicationStatus(application.status, t)
  return (
    <section aria-labelledby="app-h">
      <p>
        <Link to="/applications">{t('app.myApplications')}</Link>
      </p>
      <h1 id="app-h">
        {t('app.titlePrefix')} <span className="mono">{application.referenceNo}</span>
      </h1>
      <StatusStepper status={application.status} />
      <p className={`notice ${st.tone}`} role="status">
        {st.label}
      </p>
      {disbursement ? (
        <section className="card" aria-labelledby="sanction-h" role="status">
          <h2 id="sanction-h">{t('sanction.heading')}</h2>
          <dl className="facts">
            <div className="fact">
              <dt>{t('sanction.disbursement')}</dt>
              <dd>
                <Badge tone="ok">{humanize(disbursement.status)}</Badge>
              </dd>
            </div>
            <div className="fact">
              <dt>{t('sanction.instalments')}</dt>
              <dd>{disbursement.instalmentCount}</dd>
            </div>
            <div className="fact">
              <dt>{t('sanction.sanctionedOn')}</dt>
              <dd>{formatDate(disbursement.createdAt) || t('app.na')}</dd>
            </div>
          </dl>
          <ol className="timeline">
            {disbursement.instalments.map((it) => {
              const is = stepStatus(it.status, t)
              return (
                <li key={it.sequence}>
                  <div>
                    <strong>{t('sanction.instalment', { n: it.sequence })}</strong>
                  </div>
                  <Badge tone={is.tone}>{is.label}</Badge>
                </li>
              )
            })}
          </ol>
        </section>
      ) : null}
      <dl className="facts">
        <dt>{t('app.service')}</dt>
        <dd>{humanize(application.journeyCode)}</dd>
        <dt>{t('app.submitted')}</dt>
        <dd>{formatDate(application.submittedAt) || t('app.na')}</dd>
        <dt>{t('app.decisionDue')}</dt>
        <dd>{formatDate(application.slaDueAt) || t('app.na')}</dd>
      </dl>

      <h2>{t('app.departmentChecks')}</h2>
      {steps.length === 0 ? (
        <p>{t('app.checksSoon')}</p>
      ) : (
        <ol className="timeline">
          {steps.map((s) => {
            const ss = stepStatus(s.status, t)
            return (
              <li key={`${s.stepCode}:${s.departmentCode}`}>
                <div>
                  <strong>{humanize(s.stepCode)}</strong>{' '}
                  <span className="hint">{t('app.stepFrom', { dept: humanize(s.departmentCode) })}</span>
                </div>
                <Badge tone={ss.tone}>{ss.label}</Badge>
                {s.completedAt ? <span className="hint"> {t('app.received', { date: formatDate(s.completedAt) })}</span> : null}
              </li>
            )
          })}
        </ol>
      )}
      {records && records.length > 0 ? (
        <>
          <h2>{t('app.recordsFetched')}</h2>
          <p className="hint">{t('app.recordsHint')}</p>
          <ul className="stack">
            {records.map((r) => (
              <li key={`${r.stepCode}:${r.departmentCode}`} className="card">
                <h3>{r.title}</h3>
                <p className="hint">
                  {r.issuer} ({r.liveSystem})
                </p>
                {r.fields.length > 0 ? (
                  <dl className="facts">
                    {r.fields.map((f) => (
                      <div key={f.label} className="fact">
                        <dt>{f.label}</dt>
                        <dd>{f.value}</dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <p>{t('app.waitingForSystem')}</p>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <div className="actions">
        <button type="button" className="btn" onClick={reload} disabled={data.refreshing}>
          {data.refreshing ? t('app.refreshing') : t('app.refresh')}
        </button>
        {watching ? <span className="hint">{t('app.selfRefreshHint')}</span> : null}
      </div>
    </section>
  )
}
