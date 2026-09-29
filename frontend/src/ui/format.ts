/** "INCOME_CERTIFICATE" -> "Income certificate" */
export function humanize(code: string): string {
  const s = code.replace(/[_-]+/g, ' ').trim().toLowerCase()
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : code
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * ISO instant/date -> "29 Sep 2026" (UTC calendar day), or '' when missing or unparseable.
 * Formatted by hand rather than Intl so the wording never depends on the browser's or
 * Node's locale data.
 */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`
}

/** ISO instant -> "29 Sep 2026, 10:15 UTC", or '' when missing or unparseable. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getUTCHours()).padStart(2, '0')
  const mm = String(d.getUTCMinutes()).padStart(2, '0')
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}, ${hh}:${mm} UTC`
}

/** 93784 -> "1d 2h", 5400 -> "1h 30m", 42 -> "42s". Negative values are shown unsigned. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return 'n/a'
  const s = Math.floor(Math.abs(seconds))
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s % 60}s`
  return `${s}s`
}

/** 0.9375 -> "93.8%"; null -> "n/a". `ratio` is 0..1. */
export function formatRatio(ratio: number | null | undefined): string {
  return ratio === null || ratio === undefined || Number.isNaN(ratio) ? 'n/a' : `${(ratio * 100).toFixed(1)}%`
}

/** A percentage already on a 0..100 scale -> "93.8%"; null -> "n/a". */
export function formatPercent(percent: number | null | undefined): string {
  return percent === null || percent === undefined || Number.isNaN(percent) ? 'n/a' : `${percent.toFixed(1)}%`
}

/** 12.3456 -> "12 ms"; null -> "n/a". */
export function formatMs(ms: number | null | undefined): string {
  return ms === null || ms === undefined || Number.isNaN(ms) ? 'n/a' : `${Math.round(ms)} ms`
}

/** First 8 characters of a UUID, for tables that link to the full record elsewhere. */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id
}
