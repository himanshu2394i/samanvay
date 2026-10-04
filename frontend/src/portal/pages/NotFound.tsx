import { Link } from 'react-router-dom'

export function NotFoundPage({ what = 'page' }: { what?: string }) {
  return (
    <div className="empty">
      <h1>We could not find that {what}</h1>
      <p>The link may be old or mistyped.</p>
      <Link className="btn" to="/">
        Go to services
      </Link>
    </div>
  )
}
