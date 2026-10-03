import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import type {
  ConsentArtifact,
  ConsentRequest,
  DepartmentLinkNeed,
  JourneyDefinition,
  JourneyInstance,
  LinkProofKind,
  LinkProofProviderInfo,
} from '../../../api/types'
import { useT } from '../../../i18n'
import type { TranslationKey } from '../../../i18n'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { useCitizen } from '../CitizenContext'
import {
  allLinked,
  buildLinkRequest,
  pollForApplication,
  supportedProviders,
  unsupportedProviders,
} from '../lib/applyFlow'
import { formatDate, humanize } from '../lib/format'

type Step = 'connect' | 'consent' | 'submit'
const STEPS: { id: Step; labelKey: TranslationKey }[] = [
  { id: 'connect', labelKey: 'apply.step.connect' },
  { id: 'consent', labelKey: 'apply.step.consent' },
  { id: 'submit', labelKey: 'apply.step.submit' },
]

export function ApplyPage() {
  const { code = '' } = useParams()
  const api = useCitizenApi()
  const t = useT()
  const { citizenId } = useCitizen()
  const journey = useAsync(() => api.getJourney(code), code)

  if (journey.status === 'loading') return <Loading label={t('apply.loading')} />
  if (journey.status === 'error') return <ErrorNotice error={journey.error} onRetry={journey.reload} />
  if (!citizenId) return null // RequireProfile redirects before this renders
  return <Wizard journey={journey.data} citizenId={citizenId} />
}

function Wizard({ journey, citizenId }: { journey: JourneyDefinition; citizenId: string }) {
  const t = useT()
  const [step, setStep] = useState<Step>('connect')
  const [artifact, setArtifact] = useState<ConsentArtifact | null>(null)
  const current = STEPS.findIndex((s) => s.id === step)

  return (
    <section aria-labelledby="apply-h">
      <p>
        <Link to={`/services/${encodeURIComponent(journey.code)}`}>{t('apply.backToService')}</Link>
      </p>
      <h1 id="apply-h">{t('apply.title', { name: journey.name })}</h1>
      <ol className="steps" aria-label={t('apply.stepsLabel')}>
        {STEPS.map((s, i) => (
          <li key={s.id} className={i < current ? 'done' : i === current ? 'now' : ''} aria-current={i === current ? 'step' : undefined}>
            <span className="n">{i + 1}</span> {t(s.labelKey)}
          </li>
        ))}
      </ol>

      {step === 'connect' ? (
        <ConnectStep journey={journey} citizenId={citizenId} onContinue={() => setStep('consent')} />
      ) : null}
      {step === 'consent' ? (
        <ConsentStep
          journey={journey}
          citizenId={citizenId}
          artifact={artifact}
          onGranted={setArtifact}
          onBack={() => setStep('connect')}
          onContinue={() => setStep('submit')}
        />
      ) : null}
      {step === 'submit' ? (
        <SubmitStep journey={journey} citizenId={citizenId} onBack={() => setStep('connect')} />
      ) : null}
    </section>
  )
}

// --- step 1: link department accounts ------------------------------------------------------
// GET  /api/identity/citizens/{id}/connect-accounts?journeyCode=  (which departments, which linked)
// POST /api/identity/links                                        (prove control of a department account)

function ConnectStep({ journey, citizenId, onContinue }: { journey: JourneyDefinition; citizenId: string; onContinue: () => void }) {
  const api = useCitizenApi()
  const t = useT()
  const accounts = useAsync(() => api.connectAccounts(citizenId, journey.code), `${citizenId}:${journey.code}`)

  if (accounts.status === 'loading') return <Loading label={t('connect.checking')} />
  if (accounts.status === 'error') return <ErrorNotice error={accounts.error} onRetry={accounts.reload} />

  const { departments, providers } = accounts.data
  const ready = allLinked(departments)
  const linkedCount = departments.filter((d) => d.linked).length

  return (
    <div aria-labelledby="connect-h" role="group">
      <h2 id="connect-h">{t('connect.heading')}</h2>
      <p>{t(departments.length === 1 ? 'connect.introOne' : 'connect.introMany', { count: departments.length })}</p>
      <p role="status">{t('connect.connectedCount', { linked: linkedCount, total: departments.length })}</p>
      <ul className="stack">
        {departments.map((d) => (
          <li key={d.departmentCode} className="card">
            <DepartmentCard need={d} providers={providers} citizenId={citizenId} onLinked={accounts.reload} />
          </li>
        ))}
      </ul>
      <div className="actions">
        <button type="button" className="btn primary" disabled={!ready} onClick={onContinue}>
          {t('connect.continue')}
        </button>
        {!ready ? <span className="hint">{t('connect.connectAllHint')}</span> : null}
      </div>
    </div>
  )
}

function DepartmentCard({
  need,
  providers,
  citizenId,
  onLinked,
}: {
  need: DepartmentLinkNeed
  providers: LinkProofProviderInfo[]
  citizenId: string
  onLinked: () => void
}) {
  const api = useCitizenApi()
  const t = useT()
  const usable = supportedProviders(providers)
  const other = unsupportedProviders(providers)
  const [provider, setProvider] = useState<LinkProofKind>(usable[0]?.kind ?? 'LOCAL_ID_OTP')
  const [localIdType, setLocalIdType] = useState(need.localIdType ?? need.departmentCode)
  const [localId, setLocalId] = useState('')
  const [otp, setOtp] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.assertLink(
        buildLinkRequest({ citizenId, departmentCode: need.departmentCode, provider, localIdType, localId, otp }),
      )
      onLinked()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const headingId = `dept-${need.departmentCode}`
  return (
    <>
      <h3 id={headingId}>
        {need.departmentName}{' '}
        <Badge tone={need.linked ? 'ok' : 'warn'}>{need.linked ? t('dept.connected') : t('dept.notConnected')}</Badge>
      </h3>
      <p className="hint">{t('dept.provides', { categories: need.categories.map(humanize).join(', ') })}</p>
      {need.linked ? null : usable.length === 0 ? (
        <p className="notice warn">
          {t('dept.noProvider')}
          {other.length ? ` ${t('dept.offeredByServer', { providers: other.map((p) => p.label).join(', ') })}` : ''}
        </p>
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} aria-labelledby={headingId}>
          <div className="field">
            <label htmlFor={`${headingId}-provider`}>{t('dept.howProve')}</label>
            <select id={`${headingId}-provider`} value={provider} onChange={(e) => setProvider(e.target.value as LinkProofKind)}>
              {usable.map((p) => (
                <option key={p.kind} value={p.kind}>
                  {p.label}
                </option>
              ))}
            </select>
          </div>
          <Field label={t('dept.idType')} value={localIdType} onChange={(e) => setLocalIdType(e.target.value)} required hint={t('dept.idTypeHint')} />
          <Field label={t('dept.yourId')} value={localId} onChange={(e) => setLocalId(e.target.value)} required />
          {provider === 'LOCAL_ID_OTP' ? (
            <Field
              label={t('dept.oneTimeCode')}
              value={otp}
              onChange={(e) => setOtp(e.target.value)}
              required
              inputMode="numeric"
              autoComplete="one-time-code"
              hint={t('dept.otpHint')}
            />
          ) : null}
          {error ? <ErrorNotice error={error} /> : null}
          <button type="submit" className="btn" disabled={busy} aria-busy={busy || undefined}>
            {busy ? t('dept.connecting') : t('dept.connect', { name: need.departmentName })}
          </button>
        </form>
      )}
    </>
  )
}

// --- step 2: consent ---------------------------------------------------------------------
// POST /api/consent/requests                {citizenId, purposeCode}  -> what is being asked
// POST /api/consent/requests/{id}/grant     {citizenId}               -> the consent record

function ConsentStep({
  journey,
  citizenId,
  artifact,
  onGranted,
  onBack,
  onContinue,
}: {
  journey: JourneyDefinition
  citizenId: string
  artifact: ConsentArtifact | null
  onGranted: (a: ConsentArtifact) => void
  onBack: () => void
  onContinue: () => void
}) {
  const api = useCitizenApi()
  const t = useT()
  const [request, setRequest] = useState<ConsentRequest | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function run<T>(action: () => Promise<T>, then: (v: T) => void) {
    setBusy(true)
    setError(null)
    try {
      then(await action())
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div role="group" aria-labelledby="consent-h">
      <h2 id="consent-h">{t('consent.heading')}</h2>
      {artifact ? (
        <div className="notice ok" role="status">
          <p>
            {t('consent.grantedPrefix', { date: formatDate(artifact.validUntil) })}{' '}
            <Link to="/consents">{t('nav.consents')}</Link>
            {t('consent.grantedSuffix')}
          </p>
        </div>
      ) : request ? (
        <div className="card">
          <p>
            <strong>{humanize(request.requesterId)}</strong> {t('consent.askingSuffix')}
          </p>
          <ul className="plain">
            {request.categories.map((c) => (
              <li key={c}>{humanize(c)}</li>
            ))}
          </ul>
          <p>
            {t('consent.purpose')} <em>{request.purposeText}</em>
          </p>
          <div className="actions">
            <button
              type="button"
              className="btn primary"
              disabled={busy}
              onClick={() => void run(() => api.grantConsent(request.id, citizenId), onGranted)}
            >
              {busy ? t('consent.granting') : t('consent.agree')}
            </button>
          </div>
        </div>
      ) : (
        <>
          <p>{t('consent.reviewIntro')}</p>
          <button
            type="button"
            className="btn primary"
            disabled={busy}
            onClick={() => void run(() => api.requestConsent(citizenId, journey.policy.purpose), setRequest)}
          >
            {busy ? t('consent.preparing') : t('consent.review')}
          </button>
        </>
      )}
      {error ? <ErrorNotice error={error} /> : null}
      <div className="actions">
        <button type="button" className="btn" onClick={onBack}>
          {t('consent.back')}
        </button>
        <button type="button" className="btn primary" disabled={!artifact} onClick={onContinue}>
          {t('consent.continue')}
        </button>
      </div>
    </div>
  )
}

// --- step 3: submit ------------------------------------------------------------------------
// POST /api/journeys/{code}/start           {citizenId, submission}  -> the journey instance
// GET  /api/applications?citizenId=         (poll) -> the application/reference number for it

function SubmitStep({ journey, citizenId, onBack }: { journey: JourneyDefinition; citizenId: string; onBack: () => void }) {
  const api = useCitizenApi()
  const t = useT()
  const navigate = useNavigate()
  const [instance, setInstance] = useState<JourneyInstance | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [slow, setSlow] = useState(false)

  async function submit() {
    setBusy(true)
    setError(null)
    setSlow(false)
    try {
      // Starting twice would file two applications: once started, a retry only re-polls.
      const started = instance ?? (await api.startJourney(journey.code, citizenId, {}))
      setInstance(started)
      const app = await pollForApplication(api, citizenId, started)
      if (app) void navigate(`/applications/${encodeURIComponent(app.referenceNo)}`)
      else setSlow(true)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div role="group" aria-labelledby="submit-h">
      <h2 id="submit-h">{t('submit.heading')}</h2>
      <p>
        {t('submit.introPrefix')} <strong>{journey.name}</strong>
        {t('submit.introSuffix')}
      </p>
      {error ? <ErrorNotice error={error} /> : null}
      {slow ? (
        <p className="notice warn" role="status">
          {t('submit.slowPrefix')} <Link to="/applications">{t('nav.applications')}</Link> {t('submit.slowSuffix')}
        </p>
      ) : null}
      <div className="actions">
        <button type="button" className="btn" onClick={onBack} disabled={busy || instance !== null}>
          {t('submit.back')}
        </button>
        <button type="button" className="btn primary" disabled={busy} aria-busy={busy || undefined} onClick={() => void submit()}>
          {busy ? t('submit.submitting') : instance ? t('submit.checkNumber') : t('submit.submit')}
        </button>
      </div>
    </div>
  )
}
