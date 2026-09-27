package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakTokenValidatorTest {

    private final KeycloakTokenValidator staff =
            new KeycloakTokenValidator("samanvay-api", List.of("samanvay-staff-ui", "dept-scholarship-dev"));

    @Test
    void acceptsAudienceAllowedClientAndBearer() {
        assertThat(staff.validate(jwt(Map.of("aud", List.of("samanvay-api", "account"), "azp", "samanvay-staff-ui", "typ", "Bearer")))
                        .hasErrors())
                .isFalse();
        assertThat(staff.validate(jwt(Map.of("aud", "samanvay-api", "azp", "dept-scholarship-dev", "typ", "Bearer")))
                        .hasErrors())
                .isFalse();
    }

    @Test
    void refusesMissingOrWrongAudience() {
        assertThat(staff.validate(jwt(Map.of("azp", "samanvay-staff-ui", "typ", "Bearer"))).hasErrors()).isTrue();
        assertThat(staff.validate(jwt(Map.of("aud", List.of("account"), "azp", "samanvay-staff-ui", "typ", "Bearer")))
                        .hasErrors())
                .isTrue();
    }

    @Test
    void refusesMissingOrUnlistedAzp() {
        assertThat(staff.validate(jwt(Map.of("aud", "samanvay-api", "typ", "Bearer"))).hasErrors()).isTrue();
        for (String client : List.of("admin-cli", "account-console", "samanvay-citizen-ui", "security-admin-console")) {
            assertThat(staff.validate(jwt(Map.of("aud", "samanvay-api", "azp", client, "typ", "Bearer"))).hasErrors())
                    .as(client)
                    .isTrue();
        }
    }

    @Test
    void refusesNonBearerTyp() {
        for (String typ : List.of("ID", "Refresh", "Logout", "bearer")) {
            assertThat(staff.validate(jwt(Map.of("aud", "samanvay-api", "azp", "samanvay-staff-ui", "typ", typ))).hasErrors())
                    .as(typ)
                    .isTrue();
        }
        assertThat(staff.validate(jwt(Map.of("aud", "samanvay-api", "azp", "samanvay-staff-ui"))).hasErrors()).isTrue();
    }

    @Test
    void aRealmMustListItsClients() {
        assertThatThrownBy(() -> new KeycloakTokenValidator("samanvay-api", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }
}
