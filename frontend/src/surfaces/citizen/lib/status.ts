import { humanize } from './format'

export type Tone = 'ok' | 'warn' | 'bad'

export interface StatusCopy {
  tone: Tone
  label: string
  /** True once nothing more will change, so screens can stop polling. */
  final: boolean
}

/**
 * Application status codes written by tracking / orchestration (SUBMITTED,
 * PARTIALLY_VERIFIED, VERIFIED, REJECTED; CLOSED and FAILED are accepted too), in words
 * a citizen can act on.
 */
export function applicationStatus(code: string): StatusCopy {
  switch (code) {
    case 'SUBMITTED':
      return { tone: 'warn', label: 'Submitted: your application was received and checks are starting', final: false }
    case 'PARTIALLY_VERIFIED':
      return { tone: 'warn', label: 'In progress: some department records are still awaited', final: false }
    case 'VERIFIED':
      return { tone: 'ok', label: 'Verified: department records were received for your application', final: true }
    case 'CLOSED':
      return { tone: 'ok', label: 'Completed: this application is closed', final: true }
    case 'REJECTED':
      return { tone: 'bad', label: 'Needs action: this application was not approved', final: true }
    case 'FAILED':
      return { tone: 'bad', label: 'Needs action: a department record could not be fetched', final: true }
    default:
      return { tone: 'warn', label: `In progress: ${humanize(code)}`, final: false }
  }
}

export function stepStatus(code: string): { tone: Tone; label: string } {
  switch (code) {
    case 'COMPLETED':
      return { tone: 'ok', label: 'Received' }
    case 'PENDING_SOURCE':
      return { tone: 'warn', label: 'Waiting for the department' }
    case 'FAILED':
      return { tone: 'bad', label: 'Could not be fetched, needs action' }
    default:
      return { tone: 'warn', label: humanize(code) }
  }
}
