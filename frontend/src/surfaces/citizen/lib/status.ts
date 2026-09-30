import type { TFunction } from '../../../i18n'
import { enT } from '../../../i18n'
import { humanize } from './format'

export type Tone = 'ok' | 'warn' | 'bad'

export interface StatusCopy {
  tone: Tone
  /** Short label for a badge (e.g. "Submitted"). */
  short: string
  /** Full sentence a citizen can act on. */
  label: string
  /** True once nothing more will change, so screens can stop polling. */
  final: boolean
}

/**
 * Application status codes written by tracking / orchestration (SUBMITTED,
 * PARTIALLY_VERIFIED, VERIFIED, APPROVED, REJECTED; CLOSED and FAILED are accepted too), in
 * words a citizen can act on. VERIFIED is not final: the page keeps polling until the officer
 * decides (APPROVED / REJECTED / CLOSED). `t` localizes the copy; it defaults to English so
 * this stays usable outside a LanguageProvider.
 */
export function applicationStatus(code: string, t: TFunction = enT): StatusCopy {
  switch (code) {
    case 'SUBMITTED':
      return { tone: 'warn', short: t('status.submitted.short'), label: t('status.submitted.long'), final: false }
    case 'PARTIALLY_VERIFIED':
      return {
        tone: 'warn',
        short: t('status.partiallyVerified.short'),
        label: t('status.partiallyVerified.long'),
        final: false,
      }
    case 'VERIFIED':
      return { tone: 'ok', short: t('status.verified.short'), label: t('status.verified.long'), final: false }
    case 'APPROVED':
      return { tone: 'ok', short: t('status.approved.short'), label: t('status.approved.long'), final: true }
    case 'CLOSED':
      return { tone: 'ok', short: t('status.closed.short'), label: t('status.closed.long'), final: true }
    case 'REJECTED':
      return { tone: 'bad', short: t('status.rejected.short'), label: t('status.rejected.long'), final: true }
    case 'FAILED':
      return { tone: 'bad', short: t('status.failed.short'), label: t('status.failed.long'), final: true }
    default:
      return {
        tone: 'warn',
        short: t('status.inProgress.short'),
        label: t('status.inProgress.long', { detail: humanize(code) }),
        final: false,
      }
  }
}

export function stepStatus(code: string, t: TFunction = enT): { tone: Tone; label: string } {
  switch (code) {
    case 'COMPLETED':
      return { tone: 'ok', label: t('step.completed') }
    case 'PENDING_SOURCE':
      return { tone: 'warn', label: t('step.pendingSource') }
    case 'FAILED':
      return { tone: 'bad', label: t('step.failed') }
    default:
      return { tone: 'warn', label: humanize(code) }
  }
}
