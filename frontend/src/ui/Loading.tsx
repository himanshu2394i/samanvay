export function Loading({ label = 'Loading', variant = 'spinner', rows = 4 }: { label?: string; variant?: 'spinner' | 'table'; rows?: number }) {
  if (variant === 'table') {
    return (
      <div className="skeleton-table" role="status" aria-live="polite" aria-busy="true" aria-label={`${label}…`}>
        {Array.from({ length: rows }, (_, i) => (
          <div className="skeleton-row" key={i} aria-hidden="true">
            <span className="skeleton" />
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        ))}
      </div>
    )
  }
  return (
    <div className="loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span>{label}…</span>
    </div>
  )
}
