/*
 * Department login for the citizen app (docs/contracts/login-assertion.md), the same flow the static portals use.
 *
 * A citizen proves who they are AT a department by logging in at that department's own login. The app asks Samanvay for the
 * department's login address (Samanvay keeps a one-time state it will check later), remembers where the citizen was, and sends
 * the browser there. The department sends the citizen back to this app's /dept-callback route with a signed assertion; the app
 * hands it to Samanvay, which verifies the signature, the citizen, the state and the freshness, and saves the link.
 * Nothing here decides anything: this file only carries the request and the proof.
 */

const KEY = 'samanvay.deptLogin'

/** The route the department returns to (a HashRouter route, so the callback URL is the page plus #/dept-callback). */
export const CALLBACK_ROUTE = '/dept-callback'

export interface PendingLogin {
  citizenId: string
  departmentCode: string
  /** The app path the citizen was on, so they land back there. */
  returnPath: string
}

/** Where the browser is sent. A seam so tests do not navigate. */
export const navigation = {
  to: (url: string) => window.location.assign(url),
}

/** This app's own callback address, wherever it is served from (for example https://host/ or https://host/app/). */
export function callbackUrl(location: { origin: string; pathname: string }): string {
  return `${location.origin}${location.pathname}#${CALLBACK_ROUTE}`
}

/** Only an http(s) address: never a script, data or other scheme. */
export function isHttpUrl(url: string): boolean {
  try {
    const scheme = new URL(url).protocol
    return scheme === 'https:' || scheme === 'http:'
  } catch {
    return false
  }
}

/** Only a path on this site: never another origin, a protocol-relative address, or a script. */
export function safeReturnPath(path: unknown): string {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') ? path : '/'
}

export function savePending(p: PendingLogin, store: Storage = sessionStorage): void {
  store.setItem(KEY, JSON.stringify(p))
}

/** The login this browser started, forgotten as it is read: a department login is used once. */
export function takePending(store: Storage = sessionStorage): PendingLogin | null {
  let raw: string | null
  try {
    raw = store.getItem(KEY)
  } finally {
    store.removeItem(KEY)
  }
  try {
    const p = JSON.parse(raw ?? 'null') as Partial<PendingLogin> | null
    return p && p.citizenId && p.departmentCode ? { citizenId: p.citizenId, departmentCode: p.departmentCode, returnPath: safeReturnPath(p.returnPath) } : null
  } catch {
    return null
  }
}

/** What the department sent back: the signed assertion, or a plain reason it did not work. */
export function readCallback(search: string): { assertion: string } | { error: string } {
  const params = new URLSearchParams(search)
  if (params.get('error')) return { error: 'The department login did not complete. Please try again.' }
  const assertion = params.get('assertion')
  return assertion ? { assertion } : { error: 'The department did not send back a login result. Please try again.' }
}
