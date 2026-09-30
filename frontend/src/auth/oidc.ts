import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { OidcConfig } from './config'

/**
 * The page the IdP sends the browser back to: this document's own URL without query or
 * hash. The app uses hash routing, so the same URL works at the dev origin and when the
 * build is served by Spring under /app/ (it matches the realm client's `<origin>/*`).
 */
export function currentRedirectUri(loc: Pick<Location, 'origin' | 'pathname'> = window.location): string {
  return loc.origin + loc.pathname
}

/**
 * Authorization Code + PKCE (S256) with a public client. Tokens live in sessionStorage
 * (per tab, gone when it closes). The short-lived (5 min) access token is renewed in the
 * background from the refresh token Keycloak issues, so an active session stays signed in
 * until the Keycloak SSO session itself ends — instead of dropping every 5 minutes.
 */
export function createUserManager(cfg: OidcConfig): UserManager {
  const redirect = currentRedirectUri()
  return new UserManager({
    authority: cfg.authority,
    client_id: cfg.clientId,
    redirect_uri: redirect,
    post_logout_redirect_uri: redirect,
    response_type: 'code',
    scope: 'openid',
    loadUserInfo: false,
    // Silently refresh the access token before it expires (via the refresh_token grant;
    // CORS-allowed by the realm client's webOrigins, so no silent-renew iframe is needed).
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  })
}

/** True when the URL is an IdP redirect back to us (?code=&state= or ?error=&state=). */
export function isCallbackUrl(search: string): boolean {
  const p = new URLSearchParams(search)
  return p.has('state') && (p.has('code') || p.has('error'))
}
