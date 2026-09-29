import type { RealmKey } from './config'

/**
 * One app, two Keycloak realms. Which realm this page load talks to is decided once, at
 * start-up, from where the browser is:
 *   - a hash route under /staff  -> the staff realm; everything else -> the citizen realm
 *   - an IdP redirect back to us (?code=&state=) carries no route, so the realm the
 *     sign-in was started for is remembered in sessionStorage (per tab) just before the
 *     redirect and read back here.
 * Each realm has its own UserManager, so tokens never mix (oidc-client-ts keys a stored
 * session by authority + client id).
 */
const KEY = 'samanvay.signin-realm'

const STAFF_HASH = /^#\/staff(?:[/?]|$)/

export function realmFromHash(hash: string): RealmKey {
  return STAFF_HASH.test(hash) ? 'staff' : 'citizen'
}

export function rememberSigninRealm(realm: RealmKey, storage: Pick<Storage, 'setItem'> = window.sessionStorage): void {
  try {
    storage.setItem(KEY, realm)
  } catch {
    /* storage blocked: a citizen callback still works; a staff callback falls back to citizen and fails closed */
  }
}

export function clearSigninRealm(storage: Pick<Storage, 'removeItem'> = window.sessionStorage): void {
  try {
    storage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}

/** Realm for this page load. `isCallback` is true for an IdP redirect back to the app. */
export function detectRealm(
  loc: Pick<Location, 'hash'>,
  isCallback: boolean,
  storage: Pick<Storage, 'getItem'> = window.sessionStorage,
): RealmKey {
  if (isCallback) {
    try {
      return storage.getItem(KEY) === 'staff' ? 'staff' : 'citizen'
    } catch {
      return 'citizen'
    }
  }
  return realmFromHash(loc.hash)
}
