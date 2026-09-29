import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './authContext'

/**
 * Route guard: renders its children only for a signed-in session. Otherwise it explains
 * that sign-in is needed (no automatic redirect, so a failed sign-in cannot loop) and
 * offers a button that returns the citizen to this same page afterwards.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, signIn, notice } = useAuth()
  const location = useLocation()

  if (status === 'authenticated') return <>{children}</>

  const returnTo = location.pathname + location.search
  return (
    <section className="card narrow" aria-labelledby="signin-required">
      <h1 id="signin-required">Sign in to continue</h1>
      {notice ? <p role="alert">{notice}</p> : <p>You need to sign in to use this part of Samanvay.</p>}
      <p className="hint">You sign in with a one-time code sent to your email, or with a passkey. There is no password.</p>
      <button type="button" className="btn primary" onClick={() => void signIn(returnTo)}>
        Sign in
      </button>
    </section>
  )
}
