package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

/** The link-proof token verifier applies the API's own bearer checks (real signatures from {@link TestTokens}). */
class KeycloakCitizenTokenVerifierTest {

    private final KeycloakCitizenTokenVerifier verifier = new KeycloakCitizenTokenVerifier(new SecurityRealmsProperties(
            "samanvay-api",
            null,
            new SecurityRealmsProperties.Realm(
                    TestTokens.CITIZEN_ISSUER,
                    null,
                    new FileSystemResource(TestTokens.CITIZEN_PUBLIC_KEY_PEM),
                    List.of("samanvay-citizen-ui"),
                    "samanvay-citizen-ui")));

    @Test
    void acceptsAValidCitizenTokenAndExposesSubjectAndClaims() {
        String token = TestTokens.citizenBrokered("sub-1", "dept-idp", "REVENUE", "RATION", "RC-1", Instant.now());
        var verified = verifier.verify(token).orElseThrow();
        assertThat(verified.subject()).isEqualTo("sub-1");
        assertThat(verified.claims()).containsEntry("dept_code", "REVENUE").containsEntry("dept_local_id", "RC-1");
        assertThat(verified.claims().get("auth_time")).isInstanceOf(Number.class);
        assertThat(verifier.verify(TestTokens.citizen("sub-2"))).isPresent();
        assertThat(verifier.verify("  " + token + "\n")).as("surrounding whitespace").isPresent();
    }

    @Test
    void refusesEverythingTheApiWouldRefuse() {
        Instant now = Instant.now();
        assertThat(verifier.verify(TestTokens.forgedCitizenBrokered("s", "dept-idp", "REVENUE", "RATION", "RC-1", now)))
                .as("signed by an untrusted key")
                .isEmpty();
        assertThat(verifier.verify(TestTokens.citizenWithoutAzp("s"))).as("no azp").isEmpty();
        assertThat(verifier.verify(TestTokens.citizenWithTyp("s", "ID"))).as("ID token").isEmpty();
        assertThat(verifier.verify(TestTokens.officer("s"))).as("staff realm token").isEmpty();
        assertThat(verifier.verify(TestTokens.unknownIssuerOfficer("s"))).as("unknown issuer").isEmpty();
        assertThat(verifier.verify(TestTokens.expiredOfficer("s"))).as("expired").isEmpty();
    }

    @Test
    void refusesMalformedInput() {
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify("   ")).isEmpty();
        assertThat(verifier.verify("not-a-jwt")).isEmpty();
        assertThat(verifier.verify("a.b.c")).isEmpty();
    }
}
