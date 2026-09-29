export type RealmKey = 'citizen' | 'staff'

export interface OidcConfig {
  /** OIDC issuer / authority URL, e.g. http://localhost:8180/realms/samanvay-citizen */
  authority: string
  /** The realm's public browser client (Authorization Code + PKCE, no secret). */
  clientId: string
}

interface AuthConfigBody {
  realms?: Partial<Record<RealmKey, { issuer?: string; clientId?: string }>>
}

/** Picks one realm's issuer + client out of the API's GET /ui/auth-config body. */
export function parseAuthConfig(body: unknown, realm: RealmKey): OidcConfig {
  const r = (body as AuthConfigBody | null)?.realms?.[realm]
  if (!r?.issuer || !r.clientId) {
    throw new Error(`Sign-in is not configured for the ${realm} realm.`)
  }
  return { authority: r.issuer, clientId: r.clientId }
}

/**
 * Where the SPA learns which Keycloak realm and client to use. Default: ask the API
 * (GET /ui/auth-config, public), the same way the static portals do, so the SPA carries
 * no IdP address of its own. VITE_OIDC_AUTHORITY + VITE_OIDC_CLIENT_ID override the citizen
 * realm; VITE_STAFF_OIDC_AUTHORITY + VITE_STAFF_OIDC_CLIENT_ID override the staff realm.
 */
export async function loadOidcConfig(
  realm: RealmKey = 'citizen',
  opts: { fetchImpl?: typeof fetch; env?: Record<string, string | undefined> } = {},
): Promise<OidcConfig> {
  const env = opts.env ?? (import.meta.env as Record<string, string | undefined>)
  const authority = realm === 'staff' ? env.VITE_STAFF_OIDC_AUTHORITY : env.VITE_OIDC_AUTHORITY
  const clientId = realm === 'staff' ? env.VITE_STAFF_OIDC_CLIENT_ID : env.VITE_OIDC_CLIENT_ID
  if (authority && clientId) return { authority, clientId }
  const doFetch = opts.fetchImpl ?? ((...a: Parameters<typeof fetch>) => globalThis.fetch(...a))
  let res: Response
  try {
    res = await doFetch('/ui/auth-config', { cache: 'no-store', headers: { Accept: 'application/json' } })
  } catch {
    throw new Error('Could not reach the Samanvay API to load sign-in settings. Is the application running?')
  }
  if (!res.ok) throw new Error(`Sign-in settings are unavailable (HTTP ${res.status}).`)
  return parseAuthConfig(await res.json(), realm)
}
