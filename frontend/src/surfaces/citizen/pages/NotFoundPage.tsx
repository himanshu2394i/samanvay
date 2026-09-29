import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <section className="card narrow">
      <h1>Page not found</h1>
      <p>There is nothing at this address.</p>
      <Link className="btn" to="/">
        Go to the start page
      </Link>
    </section>
  )
}
