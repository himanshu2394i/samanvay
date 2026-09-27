package com.samanvay.shared.security;

/**
 * Published for every 403 answered on {@code /api/**} (401s are only counted
 * and logged, see {@code ApiAccessAuditFilter}). {@code audit} listens and
 * writes one hash-chained entry through {@code AuditService}; this package does
 * not depend on {@code audit} (which already depends on shared), so there is no
 * module cycle and no second log.
 *
 * @param status the refusal status (403)
 * @param route method and matched route template, e.g. {@code GET /api/journeys/instances/{id}}
 *     ({@code UNMATCHED} if no route matched) - never the raw request URL
 * @param actorKind {@code ANONYMOUS} when no valid token was presented, otherwise
 *     the {@link com.samanvay.shared.PrincipalRef.Kind} name
 * @param actorId token subject / client id, or {@code anonymous}
 * @param reason short machine reason (never token material)
 */
public record ApiAccessRefused(int status, String route, String actorKind, String actorId, String reason) {

    public static final String ANONYMOUS = "ANONYMOUS";

    /** Request attribute naming the machine reason of a 401/403, recorded on its audit entry. */
    public static final String REASON_ATTRIBUTE = ApiAccessRefused.class.getName() + ".reason";
}
