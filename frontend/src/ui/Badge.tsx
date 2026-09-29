import type { ReactNode } from 'react'

export function Badge({ tone, children }: { tone: 'ok' | 'warn' | 'bad' | 'neutral'; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>
}
