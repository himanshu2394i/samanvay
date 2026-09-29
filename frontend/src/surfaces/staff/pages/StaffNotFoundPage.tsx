import { Link } from 'react-router-dom'

export function StaffNotFoundPage() {
  return (
    <section className="card narrow" aria-labelledby="nf-h">
      <h1 id="nf-h">Page not found</h1>
      <p>
        There is no staff page at this address. <Link to="/staff">Back to your consoles</Link>.
      </p>
    </section>
  )
}
