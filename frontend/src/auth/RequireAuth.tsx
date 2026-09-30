import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './authContext'
import type { RealmKey } from './config'

/** One-click demo identities per realm, shown only in the dev/demo build. */
const DEMO_ROLES: Record<RealmKey, { role: string; label: string }[]> = {
  staff: [
    { role: 'admin', label: 'Admin' },
    { role: 'officer', label: 'Officer' },
    { role: 'reviewer', label: 'Reviewer' },
  ],
  citizen: [{ role: 'citizen', label: 'Citizen' }],
}

/** Demo/dev only: sign in as a demo role with no password, so a judge can walk the demo. */
function DemoSignIn({ realm, demoSignIn }: { realm: RealmKey; demoSignIn: (role: string) => Promise<void> }) {
  return (
    <div className="demo-signin" style={{ marginTop: '1rem', paddingTop: '1rem', borderTop: '1px solid var(--border, #ddd)' }}>
      <p className="hint">
        <strong>Just exploring?</strong> Sign in as a demo user — no password, no code. (Demo build only.)
      </p>
      <div className="actions">
        {DEMO_ROLES[realm].map((r) => (
          <button key={r.role} type="button" className="btn" onClick={() => void demoSignIn(r.role)}>
            Demo: {r.label}
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Route guard: renders its children only for a signed-in session. Otherwise it explains
 * that sign-in is needed (no automatic redirect, so a failed sign-in cannot loop) and
 * offers a button that returns the citizen to this same page afterwards.
 */
export function RequireAuth({ children, hint, audience = 'use this part of Samanvay' }: {
  children: ReactNode
  /** Sign-in guidance shown under the prompt; defaults to the citizen realm's methods. */
  hint?: ReactNode
  audience?: string
}) {
  const { status, signIn, notice, realm, devSignIn, demoSignIn } = useAuth()
  const location = useLocation()

  if (status === 'authenticated') return <>{children}</>

  const returnTo = location.pathname + location.search
  return (
    <section className="card narrow" aria-labelledby="signin-required">
      <h1 id="signin-required">Sign in to continue</h1>
      {notice ? <p role="alert">{notice}</p> : <p>You need to sign in to {audience}.</p>}
      <p className="hint">
        {hint ?? 'You sign in with a one-time code sent to your email, or with a passkey. There is no password.'}
      </p>
      <button type="button" className="btn primary" onClick={() => void signIn(returnTo)}>
        Sign in
      </button>
      {devSignIn ? <DemoSignIn realm={realm} demoSignIn={demoSignIn} /> : null}
    </section>
  )
}
