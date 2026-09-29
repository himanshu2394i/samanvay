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
