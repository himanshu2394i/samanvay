package com.samanvay.shared.security;

/**
 * Published for every 401/403 answered on {@code /api/**}. {@code audit}
 * listens and writes one hash-chained entry through {@code AuditService}; this
 * package does not depend on {@code audit} (which already depends on shared),
 * so there is no module cycle and no second log.
 *
 * @param status 401 or 403
 * @param actorKind {@code ANONYMOUS} when no valid token was presented, otherwise
 *     the {@link com.samanvay.shared.PrincipalRef.Kind} name
 * @param actorId token subject / client id, or {@code anonymous}
 * @param reason short machine reason (never token material)
 */
public record ApiAccessRefused(int status, String method, String path, String actorKind, String actorId, String reason) {

    public static final String ANONYMOUS = "ANONYMOUS";
}
