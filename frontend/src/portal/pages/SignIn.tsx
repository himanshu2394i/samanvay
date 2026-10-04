import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { errorText } from '../api'
import { usePortal } from '../context'
import { Notice } from '../ui'

export function SignInPage() {
  const { api, session, setSession, branding } = usePortal()
  const navigate = useNavigate()
  const [mobile, setMobile] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [ticket, setTicket] = useState<{ value: string; masked: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  if (session) return <Navigate to="/" replace />

  async function submitCredentials(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const r = await api.signIn(mobile.trim(), password)
      setTicket({ value: r.ticket, masked: r.masked })
      setCode('')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  async function submitCode(e: FormEvent) {
    e.preventDefault()
    if (!ticket) return
    setBusy(true)
    setError(null)
    try {
      const s = await api.verify(ticket.value, code.trim())
      setSession(s)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err) // wrong code: keep the ticket so they can try again
    } finally {
      setBusy(false)
    }
  }

  function differentNumber() {
    setTicket(null)
    setPassword('')
    setCode('')
    setError(null)
  }

  return (
    <section className="card narrow" aria-labelledby="signin-title">
      <h1 id="signin-title">Sign in</h1>
      {!ticket ? (
        <form onSubmit={(e) => void submitCredentials(e)} noValidate>
          <p className="lead">
            Sign in to use the services of {branding?.name ?? 'this department'}.
          </p>
          <div className="field">
            <label htmlFor="mobile">Mobile number</label>
            <input
              id="mobile"
              name="mobile"
              type="tel"
              inputMode="numeric"
              autoComplete="username"
              required
              value={mobile}
              onChange={(e) => setMobile(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          {error ? <Notice tone="bad">{errorText(error)}</Notice> : null}
          <button type="submit" className="btn" disabled={busy || !mobile.trim() || !password}>
            {busy ? 'Checking' : 'Continue'}
          </button>
        </form>
      ) : (
        <form onSubmit={(e) => void submitCode(e)} noValidate>
          <p className="lead">
            Enter the one-time code for the number {ticket.masked}.
          </p>
          <div className="field">
            <label htmlFor="otp">One-time code</label>
            <input
              id="otp"
              name="otp"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              required
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
          {error ? <Notice tone="bad">{errorText(error)}</Notice> : null}
          <div className="actions">
            <button type="submit" className="btn" disabled={busy || !code.trim()}>
              {busy ? 'Checking' : 'Sign in'}
            </button>
            <button type="button" className="btn link" onClick={differentNumber}>
              Use a different number
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
