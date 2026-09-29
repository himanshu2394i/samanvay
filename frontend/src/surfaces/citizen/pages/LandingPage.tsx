import { Link } from 'react-router-dom'
import { useAuth } from '../../../auth/authContext'

const STEPS = [
  ['Browse services', 'See what each government service needs before you start.'],
  ['Connect department accounts', 'Link the departments that hold your records, once.'],
  ['Give consent', 'Say exactly which records may be fetched, and for what purpose.'],
  ['Submit', 'We fetch the records for you: no document uploads.'],
  ['Track', 'Follow each department check until your application is decided.'],
]

export function LandingPage() {
  const { status, signIn, notice } = useAuth()
  return (
    <>
      <section className="hero">
        <h1>Apply for government services without carrying papers</h1>
        <p className="lede">
          Samanvay fetches the records a service needs directly from the departments that hold them, only after you say
          yes.
        </p>
        {notice ? (
          <p className="notice warn" role="alert">
            {notice}
          </p>
        ) : null}
        {status === 'authenticated' ? (
          <Link className="btn primary" to="/services">
            Browse services
          </Link>
        ) : (
          <>
            <button type="button" className="btn primary" onClick={() => void signIn('/services')}>
              Sign in to start
            </button>
            <p className="hint">Sign in with a one-time code sent to your email, or with a passkey. There is no password.</p>
          </>
        )}
      </section>
      <section aria-labelledby="how">
        <h2 id="how">How it works</h2>
        <ol className="how">
          {STEPS.map(([title, text]) => (
            <li key={title}>
              <strong>{title}</strong>
              <span>{text}</span>
            </li>
          ))}
        </ol>
      </section>
    </>
  )
}
