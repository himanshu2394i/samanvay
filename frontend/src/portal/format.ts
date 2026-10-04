/** "INCOME_CERTIFICATE" -> "Income certificate", "VERIFIED" -> "Verified". */
export function humanize(code: string): string {
  const s = code.replace(/[_-]+/g, ' ').trim().toLowerCase()
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : code
}

/** ISO instant -> a date in the browser's own locale, or '' when missing or unreadable. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export type Tone = 'ok' | 'bad' | 'neutral'

const GOOD = new Set(['VERIFIED', 'COMPLETED', 'COMPLETE', 'APPROVED', 'ISSUED', 'DONE', 'SUCCEEDED', 'SUCCESS', 'RECEIVED'])
const BAD = new Set(['FAILED', 'REJECTED', 'ERROR', 'CANCELLED', 'EXPIRED', 'DENIED'])

export function statusTone(status: string): Tone {
  const s = status.toUpperCase()
  return GOOD.has(s) ? 'ok' : BAD.has(s) ? 'bad' : 'neutral'
}

/** "categoryCode" or "category_code" -> "Category code", for records whose shape we do not know. */
export function keyLabel(key: string): string {
  return humanize(key.replace(/([a-z0-9])([A-Z])/g, '$1 $2'))
}
