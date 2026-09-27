package com.samanvay.shared.security;

/**
 * Platform roles. Keycloak realm roles (lower-case) are mapped onto these by
 * {@link KeycloakJwtConverter}; route rules in {@link SecurityConfig} refer to
 * them via {@code hasRole(...)}.
 *
 * <p>People roles come from exactly one realm each: {@code CITIZEN} only from
 * the citizen realm, the three staff roles only from the staff realm. A token
 * from the citizen realm that somehow carries {@code admin} gets nothing for it.
 * {@code DEPARTMENT} is only granted to client-credentials (service-account)
 * tokens and is never combined with a people role.
 */
public final class SamanvayRoles {

    public static final String CITIZEN = "CITIZEN";
    public static final String OFFICER = "OFFICER";
    public static final String REVIEWER = "REVIEWER";
    public static final String ADMIN = "ADMIN";
    public static final String DEPARTMENT = "DEPARTMENT";

    /** Keycloak realm role carried by department service accounts. */
    public static final String KEYCLOAK_DEPARTMENT_ROLE = "department";

    /** OAuth2 scope prefix: one client scope per data source, e.g. {@code source:revenue-rest-mock}. */
    public static final String DATA_SOURCE_SCOPE_PREFIX = "source:";

    private SamanvayRoles() {}
}
