package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.PrincipalRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtConverterTest {

    private final KeycloakJwtConverter staff = new KeycloakJwtConverter(KeycloakJwtConverter.RealmKind.STAFF);
    private final KeycloakJwtConverter citizen = new KeycloakJwtConverter(KeycloakJwtConverter.RealmKind.CITIZEN);

    @Test
    void staffRolesMapToPlatformRoles() {
        var auth = (SamanvayAuthentication) staff.convert(jwt("u1", Map.of("realm_access", Map.of("roles", List.of("officer", "Reviewer", "offline_access")))));
        assertThat(authorities(auth)).containsExactlyInAnyOrder("ROLE_OFFICER", "ROLE_REVIEWER");
        assertThat(auth.caller().principal()).isEqualTo(new PrincipalRef(PrincipalRef.Kind.OFFICER, "u1"));
        assertThat(auth.getName()).isEqualTo("u1");
    }

    @Test
    void citizenRealmOnlyEverGrantsCitizen() {
        var auth = (SamanvayAuthentication) citizen.convert(jwt("c1", Map.of("realm_access", Map.of("roles", List.of("citizen", "admin", "officer", "department")))));
        assertThat(authorities(auth)).containsExactly("ROLE_CITIZEN");
        assertThat(auth.caller().principal().kind()).isEqualTo(PrincipalRef.Kind.CITIZEN);
    }

    @Test
    void staffRealmNeverGrantsCitizen() {
        var auth = staff.convert(jwt("s1", Map.of("realm_access", Map.of("roles", List.of("citizen")))));
        assertThat(authorities(auth)).isEmpty();
    }

    @Test
    void departmentClientGetsOnlyDepartmentAndSourceScopesAndIsNamedByClientId() {
        var auth = (SamanvayAuthentication) staff.convert(jwt("svc-uuid", Map.of(
                "client_id", "dept-a",
                "azp", "dept-a",
                "scope", "profile source:revenue-rest-mock source:dbt-rest-mock source:",
                "realm_access", Map.of("roles", List.of("department", "admin")))));
        assertThat(authorities(auth))
                .containsExactlyInAnyOrder("ROLE_DEPARTMENT", "SCOPE_source:revenue-rest-mock", "SCOPE_source:dbt-rest-mock");
        assertThat(auth.caller().dataSourceScopes()).containsExactlyInAnyOrder("revenue-rest-mock", "dbt-rest-mock");
        assertThat(auth.caller().principal()).isEqualTo(new PrincipalRef(PrincipalRef.Kind.DEPARTMENT, "dept-a"));
    }

    @Test
    void peopleTokenWithDepartmentRoleIsNotADepartment() {
        assertThat(authorities(staff.convert(jwt("p", Map.of("realm_access", Map.of("roles", List.of("department")))))))
                .isEmpty();
    }

    @Test
    void serviceAccountWithoutDepartmentRoleGetsNothingAndNeverPeopleRoles() {
        assertThat(authorities(staff.convert(jwt("svc", Map.of(
                        "client_id", "some-client", "azp", "some-client",
                        "realm_access", Map.of("roles", List.of("officer", "admin")))))))
                .isEmpty();
    }

    @Test
    void mismatchedAzpIsRejectedAsDepartment() {
        assertThat(authorities(staff.convert(jwt("svc", Map.of(
                        "client_id", "dept-a", "azp", "other",
                        "realm_access", Map.of("roles", List.of("department")))))))
                .isEmpty();
    }

    @Test
    void serviceAccountWithoutAzpIsNotADepartment() {
        // no fallback to client_id: azp must be present and equal it
        assertThat(authorities(staff.convert(jwt("svc", Map.of(
                        "client_id", "dept-a",
                        "realm_access", Map.of("roles", List.of("department")))))))
                .isEmpty();
    }

    @Test
    void jtiIsTheSessionProof() {
        var auth = (SamanvayAuthentication) citizen.convert(jwt("c1", Map.of("realm_access", Map.of("roles", List.of("citizen")))));
        assertThat(auth.caller().sessionId()).isEqualTo("jti-1");
    }

    private static List<String> authorities(org.springframework.security.core.Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(String sub, Map<String, Object> claims) {
        var b = Jwt.withTokenValue("t").header("alg", "RS256").subject(sub).jti("jti-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }
}
