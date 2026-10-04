import { humanize } from '../../../ui/format'

export type Tone = 'ok' | 'warn' | 'bad' | 'neutral'

/** Officer wording for the application status codes written by tracking / orchestration. */
export function appStatus(code: string): { tone: Tone; label: string } {
  switch (code) {
    case 'SUBMITTED':
      return { tone: 'warn', label: 'Submitted' }
    case 'PARTIALLY_VERIFIED':
      return { tone: 'warn', label: 'Partially verified' }
    case 'VERIFIED':
      return { tone: 'ok', label: 'Verified' }
    case 'APPROVED':
      return { tone: 'ok', label: 'Approved' }
    case 'CLOSED':
      return { tone: 'neutral', label: 'Closed' }
    case 'REJECTED':
      return { tone: 'bad', label: 'Rejected' }
    case 'FAILED':
      return { tone: 'bad', label: 'Failed' }
    default:
      return { tone: 'warn', label: humanize(code) }
  }
}

/** Terminal states: nothing is waiting on a department or an officer any more. */
export function isOpenApplication(code: string): boolean {
  return !['APPROVED', 'REJECTED', 'CLOSED'].includes(code)
}

export function stepTone(code: string): { tone: Tone; label: string } {
  switch (code) {
    case 'COMPLETED':
      return { tone: 'ok', label: 'Received' }
    case 'PENDING_SOURCE':
      return { tone: 'warn', label: 'Waiting for department' }
    case 'FAILED':
      return { tone: 'bad', label: 'Failed' }
    default:
      return { tone: 'neutral', label: humanize(code) }
  }
}

export type SlaState = 'none' | 'breached' | 'due-soon' | 'on-track'

const DUE_SOON_MS = 24 * 3600 * 1000 // the same window SlaOverview.DUE_SOON uses on the server

/** Where an application stands against its SLA due time at `now`. */
export function slaState(slaDueAt: string | null, open: boolean, now: number): SlaState {
  if (!slaDueAt || !open) return 'none'
  const due = Date.parse(slaDueAt)
  if (Number.isNaN(due)) return 'none'
  if (due < now) return 'breached'
  return due - now <= DUE_SOON_MS ? 'due-soon' : 'on-track'
}

export const SLA_BADGE: Record<Exclude<SlaState, 'none'>, { tone: Tone; label: string }> = {
  breached: { tone: 'bad', label: 'SLA breached' },
  'due-soon': { tone: 'warn', label: 'Due within 24h' },
  'on-track': { tone: 'ok', label: 'On track' },
}

export function bankReviewTone(status: string): Tone {
  switch (status) {
    case 'APPROVED':
      return 'ok'
    case 'REJECTED':
      return 'bad'
    default:
      return 'warn'
  }
}

/** Data source health (GREEN, AMBER, RED, UNKNOWN) as a badge tone. */
export function healthTone(health: string): Tone {
  return health === 'GREEN' ? 'ok' : health === 'RED' ? 'bad' : health === 'AMBER' ? 'warn' : 'neutral'
}

/** A source never probed has health UNKNOWN (or none): nobody has checked it yet. */
export function isUnchecked(health: string | null | undefined): boolean {
  return !health || health === 'UNKNOWN'
}

export type SourceState = 'working' | 'blocked' | 'unchecked'

/**
 * Whether a document's source can be called working. The backend sets `working` for a published connector whose
 * source is not RED, which includes a source that was never probed; that is "not checked yet", not "working".
 * A RED source is blocked whatever the flag says.
 */
export function sourceState(working: boolean, health: string | null | undefined): SourceState {
  if (!working || health === 'RED') return 'blocked'
  return isUnchecked(health) ? 'unchecked' : 'working'
}

/** The one badge a document or category shows for its source state. */
export const SOURCE_STATE_BADGE: Record<SourceState, { tone: Tone; label: string }> = {
  working: { tone: 'ok', label: 'Yes' },
  blocked: { tone: 'bad', label: 'No' },
  unchecked: { tone: 'neutral', label: 'Not checked yet' },
}

/** Journey or connector status: PUBLISHED is live, DRAFT is waiting for a person. */
export function publishTone(status: string): Tone {
  return status === 'PUBLISHED' ? 'ok' : status === 'DRAFT' ? 'warn' : 'neutral'
}
