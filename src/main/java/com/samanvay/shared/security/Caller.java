package com.samanvay.shared.security;

import com.samanvay.shared.PrincipalRef;
import java.util.Set;

/**
 * The authenticated caller as far as business code is concerned: derived only
 * from a validated JWT, never from headers or request bodies.
 *
 * @param subject token {@code sub} (for department clients: the client id)
 * @param sessionId token {@code jti}; used as the session proof when a citizen
 *     grants consent ({@code citizen_auth_ref})
 * @param roles platform roles, without the {@code ROLE_} prefix
 * @param dataSourceScopes data-source codes a department client may fetch from
 * @param department staff realm only: the catalog department code in the token's
 *     {@code department} claim (officers: admin-managed user attribute; department
 *     clients: a hardcoded claim on the client), or {@code null}
 */
public record Caller(String subject, String sessionId, Set<String> roles, Set<String> dataSourceScopes, String department) {

    public Caller(String subject, String sessionId, Set<String> roles, Set<String> dataSourceScopes) {
        this(subject, sessionId, roles, dataSourceScopes, null);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isCitizen() {
        return hasRole(SamanvayRoles.CITIZEN);
    }

    public boolean isDepartmentClient() {
        return hasRole(SamanvayRoles.DEPARTMENT);
    }

    /**
     * The principal recorded on access grants and audit entries. Staff tokens
     * may hold several roles; the most specific operational role wins
     * (officer, then admin, then reviewer).
     */
    public PrincipalRef principal() {
        PrincipalRef.Kind kind;
        if (isDepartmentClient()) {
            kind = PrincipalRef.Kind.DEPARTMENT;
        } else if (hasRole(SamanvayRoles.OFFICER)) {
            kind = PrincipalRef.Kind.OFFICER;
        } else if (hasRole(SamanvayRoles.ADMIN)) {
            kind = PrincipalRef.Kind.ADMIN;
        } else if (hasRole(SamanvayRoles.REVIEWER)) {
            kind = PrincipalRef.Kind.REVIEWER;
        } else if (isCitizen()) {
            kind = PrincipalRef.Kind.CITIZEN;
        } else {
            throw new IllegalStateException("caller has no platform role");
        }
        return new PrincipalRef(kind, subject);
    }
}
