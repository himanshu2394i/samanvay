package com.samanvay.shared.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a Keycloak access token to platform roles, per realm.
 *
 * <ul>
 *   <li>Citizen realm: realm role {@code citizen} → {@code CITIZEN}. Nothing else.
 *   <li>Staff realm, people token: {@code officer}/{@code reviewer}/{@code admin} →
 *       {@code OFFICER}/{@code REVIEWER}/{@code ADMIN}.
 *   <li>Staff realm, client-credentials token (has a {@code client_id} claim and the
 *       realm role {@code department}): {@code DEPARTMENT} only, plus one data-source
 *       grant per {@code source:<code>} scope. The principal is the client id
 *       ({@code azp}, which must equal {@code client_id} - there is no fallback), not the
 *       synthetic service-account user.
 * </ul>
 *
 * A people token can never become a department token and vice versa: the two
 * sets of authorities are mutually exclusive by construction.
 */
final class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    enum RealmKind {
        STAFF,
        CITIZEN
    }

    private static final Set<String> STAFF_ROLES = Set.of("officer", "reviewer", "admin");

    private final RealmKind realm;

    KeycloakJwtConverter(RealmKind realm) {
        this.realm = realm;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> realmRoles = realmRoles(jwt);
        Set<String> roles = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        String subject = jwt.getSubject();

        String clientId = jwt.getClaimAsString("client_id");
        boolean serviceAccount = clientId != null && !clientId.isBlank();

        if (realm == RealmKind.CITIZEN) {
            if (!serviceAccount && realmRoles.contains("citizen")) {
                roles.add(SamanvayRoles.CITIZEN);
            }
        } else if (serviceAccount) {
            // azp is mandatory (KeycloakTokenValidator) and must be the service account's own client
            if (realmRoles.contains(SamanvayRoles.KEYCLOAK_DEPARTMENT_ROLE) && clientId.equals(jwt.getClaimAsString("azp"))) {
                roles.add(SamanvayRoles.DEPARTMENT);
                subject = clientId;
                for (String scope : scopes(jwt)) {
                    if (scope.startsWith(SamanvayRoles.DATA_SOURCE_SCOPE_PREFIX)
                            && scope.length() > SamanvayRoles.DATA_SOURCE_SCOPE_PREFIX.length()) {
                        sources.add(scope.substring(SamanvayRoles.DATA_SOURCE_SCOPE_PREFIX.length()));
                    }
                }
            }
        } else {
            for (String r : realmRoles) {
                if (STAFF_ROLES.contains(r)) {
                    roles.add(r.toUpperCase(Locale.ROOT));
                }
            }
        }

        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        sources.forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + SamanvayRoles.DATA_SOURCE_SCOPE_PREFIX + s)));
        Caller caller = new Caller(subject, jwt.getId(), Set.copyOf(roles), Set.copyOf(sources));
        return new SamanvayAuthentication(jwt, authorities, caller);
    }

    private static Set<String> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        Set<String> out = new LinkedHashSet<>();
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object r : roles) {
                if (r != null) {
                    out.add(r.toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static List<String> scopes(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        return scope == null || scope.isBlank() ? List.of() : List.of(scope.trim().split("\\s+"));
    }
}
