/** Platform roles as the API names them (SamanvayRoles). Keycloak carries them lower-case. */
export type StaffRole = 'OFFICER' | 'REVIEWER' | 'ADMIN'

export const STAFF_ROLES: readonly StaffRole[] = ['OFFICER', 'REVIEWER', 'ADMIN']

interface TokenClaims {
  realm_access?: { roles?: unknown }
  department?: unknown
}

/**
 * Reads the payload of a JWT WITHOUT verifying it. That is fine only because the result
 * is used to decide what to *show*: the browser got the token straight from Keycloak over
 * the PKCE flow, and every /api call is authorised again on the server from the verified
 * token (issuer, audience, azp and role checks in SecurityConfig). Never use it to trust
 * anything.
 */
export function decodeJwtPayload(token: string): TokenClaims {
  const part = token.split('.')[1]
  if (!part) return {}
  try {
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      Array.from(atob(b64.padEnd(Math.ceil(b64.length / 4) * 4, '=')), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    )
    const parsed: unknown = JSON.parse(json)
    return typeof parsed === 'object' && parsed !== null ? (parsed as TokenClaims) : {}
  } catch {
    return {}
  }
}

/** Realm roles of an access token, upper-cased (`officer` -> `OFFICER`). */
export function rolesFromToken(token: string): string[] {
  const roles = decodeJwtPayload(token).realm_access?.roles
  return Array.isArray(roles) ? roles.filter((r): r is string => typeof r === 'string').map((r) => r.toUpperCase()) : []
}

/** The staff member's catalog department (admin-managed claim), when the token carries one. */
export function departmentFromToken(token: string): string | null {
  const d = decodeJwtPayload(token).department
  return typeof d === 'string' && d.trim() ? d.trim() : null
}

export function staffRolesOf(roles: readonly string[]): StaffRole[] {
  return STAFF_ROLES.filter((r) => roles.includes(r))
}

export function hasAnyRole(roles: readonly string[], allowed: readonly StaffRole[]): boolean {
  return allowed.some((r) => roles.includes(r))
}
