import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useStaffApi } from '../../api/apiContext'
import type { CitizenMatch } from '../../api/staffTypes'
import { ErrorNotice } from '../../ui/ErrorNotice'

type Result =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; term: string; hits: CitizenMatch[] }
  | { status: 'error'; term: string; error: unknown }

const MIN_CHARS = 2

/**
 * The officer's way into a citizen's 360° file from the staff home: type a name (Latin or
 * Devanagari) or paste a citizen id, pick the right person, land on their CitizenViewPage.
 * The search endpoint is OFFICER-only and returns coarse fields only (birth year, not DOB),
 * so this is a lookup to disambiguate — the file itself is where the detail lives.
 */
export function CitizenSearch() {
  const api = useStaffApi()
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<Result>({ status: 'idle' })
  // Only the newest search may update the page: an older, slower answer must not replace it.
  const latest = useRef(0)

  async function search(term: string) {
    const mine = ++latest.current
    if (term.length < MIN_CHARS) {
      setResult({ status: 'idle' })
      return
    }
    setResult({ status: 'loading' })
    try {
      const hits = await api.searchCitizens(term)
      if (mine === latest.current) setResult({ status: 'done', term, hits })
    } catch (error) {
      if (mine === latest.current) setResult({ status: 'error', term, error })
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    void search(query.trim())
  }

  return (
    <section aria-labelledby="find-citizen-h" className="spaced">
      <h2 id="find-citizen-h">Find a citizen</h2>
      <p className="hint">
        Search by name or citizen id to open their file — every application they have filed and
        the consent and data-access trail behind it.
      </p>
      <form className="row" onSubmit={onSubmit} role="search">
        <label htmlFor="citizen-q" className="sr-only">
          Citizen name or id
        </label>
        <input
          id="citizen-q"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="e.g. Ramesh Kumar or a citizen id"
          autoComplete="off"
        />
        <button type="submit" className="btn primary">
          Search
        </button>
      </form>

      {result.status === 'loading' ? (
        <p className="hint" aria-live="polite">
          Searching…
        </p>
      ) : null}
      {result.status === 'error' ? <ErrorNotice error={result.error} onRetry={() => void search(result.term)} /> : null}
      {result.status === 'done' ? (
        result.hits.length === 0 ? (
          <p aria-live="polite">No citizens match “{result.term}”.</p>
        ) : (
          <ul className="plain" aria-label="Search results">
            {result.hits.map((c) => (
              <li key={c.citizenId} className="card">
                <h3>
                  <Link to={`/staff/officer/citizens/${encodeURIComponent(c.citizenId)}`}>
                    {c.nameLatin}
                    {c.nameDevanagari ? ` · ${c.nameDevanagari}` : ''}
                  </Link>
                </h3>
                <p className="hint">
                  {c.birthYear ? `Born ${c.birthYear} · ` : ''}
                  <span className="mono">{c.citizenId}</span>
                </p>
              </li>
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}
