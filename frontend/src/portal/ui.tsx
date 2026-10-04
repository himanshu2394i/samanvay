import type { ReactNode } from 'react'
import { errorText } from './api'
import { statusTone, humanize } from './format'

export function Loading({ label = 'Loading', rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div className="skeletons" role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div className="skeleton" key={i} aria-hidden="true" />
      ))}
    </div>
  )
}

/** Inline message. Errors are announced at once (role=alert), good news politely (role=status). */
export function Notice({ tone, children, action }: { tone: 'ok' | 'bad' | 'info'; children: ReactNode; action?: ReactNode }) {
  return (
    <div className={`notice ${tone}`} role={tone === 'bad' ? 'alert' : 'status'}>
      <p>{children}</p>
      {action}
    </div>
  )
}

export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Notice
      tone="bad"
      action={
        onRetry ? (
          <button type="button" className="btn secondary" onClick={onRetry}>
            Try again
          </button>
        ) : undefined
      }
    >
      {errorText(error)}
    </Notice>
  )
}

/** One numbered step of the journey page. A locked step shows only why it is locked. */
export function Step({
  n,
  title,
  state,
  lockedText,
  showWhenLocked = false,
  children,
}: {
  n: number
  title: string
  state: 'locked' | 'active' | 'done'
  lockedText?: string
  /** Keep the body (a disabled form) visible while locked. */
  showWhenLocked?: boolean
  children?: ReactNode
}) {
  const id = `step-${n}`
  return (
    <li className={`step ${state}`} aria-labelledby={id}>
      <div className="step-head">
        <span className="step-num" aria-hidden="true">
          {state === 'done' ? (
            <svg viewBox="0 0 16 16" width="16" height="16" focusable="false">
              <path d="M3 8.5l3.2 3.2L13 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          ) : (
            n
          )}
        </span>
        <h2 id={id}>
          <span className="sr-only">Step {n}: </span>
          {title}
        </h2>
        {state === 'done' ? <span className="step-state">Done</span> : null}
      </div>
      <div className="step-body">
        {state === 'locked' ? <p className="muted">{lockedText}</p> : null}
        {state !== 'locked' || showWhenLocked ? children : null}
      </div>
    </li>
  )
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${statusTone(status)}`}>{humanize(status)}</span>
}
