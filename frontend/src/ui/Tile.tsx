import type { ReactNode } from 'react'

/** A labelled headline number for the dashboards. `tone` colours the value, never alone: the label says what it is. */
export function Tile({
  label,
  value,
  sub,
  tone,
}: {
  label: string
  value: ReactNode
  sub?: ReactNode
  tone?: 'ok' | 'warn' | 'bad'
}) {
  return (
    <div className="tile">
      <p className="tile-label">{label}</p>
      <p className={`tile-value${tone ? ` ${tone}` : ''}`}>{value}</p>
      {sub ? <p className="tile-sub">{sub}</p> : null}
    </div>
  )
}
