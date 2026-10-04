import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useAsync } from '../../ui/useAsync'
import { PortalError, type Readiness } from '../api'
import { usePortal } from '../context'
import { humanize } from '../format'
import { ErrorNotice, Loading, Notice, Step } from '../ui'
import { NotFoundPage } from './NotFound'
import { ApplyForm, ConsentForm } from './JourneySteps'

export function JourneyPage() {
  const { code = '' } = useParams()
  const { api, goTo } = usePortal()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const journey = useAsync(() => api.journey(code), `journey:${code}`)
  const readiness = useAsync(() => api.readiness(code), `readiness:${code}`)
  const [consentGranted, setConsentGranted] = useState(false)
  const [linking, setLinking] = useState<string | null>(null)
  const [linkError, setLinkError] = useState<unknown>(null)

  if (journey.status === 'loading') return <Loading label="Loading the service" rows={4} />
  if (journey.status === 'error') {
    return journey.error instanceof PortalError && journey.error.status === 404 ? (
      <NotFoundPage what="service" />
    ) : (
      <ErrorNotice error={journey.error} onRetry={journey.reload} />
    )
  }
  const j = journey.data

  const departments = readiness.data?.departments ?? []
  const connected = readiness.status === 'success' && departments.every((d) => d.linked)
  const consentDone = consentGranted || readiness.data?.consentActive === true

  async function startLink(dept: string) {
    setLinking(dept)
    setLinkError(null)
    try {
      const { loginUrl } = await api.startLink(code, dept)
      goTo(loginUrl)
    } catch (e) {
      setLinkError(e)
      setLinking(null)
    }
  }

  async function submit(submission: Record<string, string>) {
    // Applications can appear a moment after submit, so remember how many there were.
    const before = await api.applications().then(
      (l) => l.length,
      () => 0,
    )
    try {
      await api.submit(code, submission)
    } catch (e) {
      if (e instanceof PortalError && e.status === 409) {
        // something needed has gone missing: re-check the steps above
        setConsentGranted(false)
        readiness.reload()
      }
      throw e
    }
    navigate('/applications', { state: { submittedAfter: before } })
  }

  return (
    <>
      <h1>{j.name}</h1>
      <p className="lead">{j.description}</p>

      <LinkNotice readiness={readiness.data} linked={params.get('linked')} failed={params.get('linkError')} />

      <ol className="steps">
        <Step n={1} title="Connect departments" state={connected ? 'done' : 'active'}>
          {readiness.status === 'loading' ? <Loading label="Checking your connections" rows={2} /> : null}
          {readiness.status === 'error' ? <ErrorNotice error={readiness.error} onRetry={readiness.reload} /> : null}
          {readiness.status === 'success' ? (
            <>
              <p className="muted">We need records from these departments. Log in at each one to connect it.</p>
              <ul className="rows">
                {departments.map((d) => (
                  <li className="row" key={d.departmentCode}>
                    <div className="row-main">
                      <strong>{d.departmentName}</strong>
                      <span className="muted">Records needed: {d.categories.map(humanize).join(', ')}</span>
                    </div>
                    <div className="row-end">
                      <span className={`badge ${d.linked ? 'ok' : 'neutral'}`}>{d.linked ? 'Connected' : 'Not connected'}</span>
                      {!d.linked ? (
                        <button
                          type="button"
                          className="btn secondary"
                          disabled={linking !== null || !d.departmentLoginAvailable}
                          onClick={() => void startLink(d.departmentCode)}
                        >
                          {linking === d.departmentCode ? 'Opening' : `Log in at ${d.departmentName}`}
                        </button>
                      ) : null}
                    </div>
                    {!d.linked && !d.departmentLoginAvailable ? (
                      <p className="muted row-note">Logging in at {d.departmentName} is not available right now.</p>
                    ) : null}
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          {linkError ? <ErrorNotice error={linkError} /> : null}
        </Step>

        <Step
          n={2}
          title="Review and give consent"
          state={!connected ? 'locked' : consentDone ? 'done' : 'active'}
          lockedText="Connect all the departments above first."
        >
          {consentDone ? (
            <p>
              <span className="badge ok">Consent given</span>
            </p>
          ) : (
            <ConsentForm
              code={code}
              onGranted={() => {
                setConsentGranted(true)
                readiness.reload()
              }}
            />
          )}
        </Step>

        <Step
          n={3}
          title="Apply"
          state={connected && consentDone ? 'active' : 'locked'}
          lockedText="Finish steps 1 and 2 to apply."
          showWhenLocked
        >
          <ApplyForm journey={j} ready={connected && consentDone} onSubmit={submit} />
        </Step>
      </ol>
    </>
  )
}

/** Where the other department's login sent the citizen back to: say how it went. */
function LinkNotice({ readiness, linked, failed }: { readiness: Readiness | undefined; linked: string | null; failed: string | null }) {
  const name = (c: string) => readiness?.departments.find((d) => d.departmentCode === c)?.departmentName ?? humanize(c)
  if (linked) return <Notice tone="ok">Connected. Your account at {name(linked)} is now linked.</Notice>
  if (failed) {
    return (
      <Notice tone="bad">
        We could not connect your account at {name(failed)}. Log in at {name(failed)} again to try once more.
      </Notice>
    )
  }
  return null
}
