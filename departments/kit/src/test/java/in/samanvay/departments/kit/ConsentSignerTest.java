package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsentSignerTest {

    @Test
    void the_statement_is_signed_with_the_department_key_and_repeats_what_samanvay_asked() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k").generate();
        UUID citizen = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        Map<String, Object> wording = Map.of("requestId", "r-1", "purposeCode", "DEMO_PURPOSE", "nonce", "n-1", "categories", List.of("INCOME_CERTIFICATE"));

        SignedJWT jwt = SignedJWT.parse(new ConsentSigner(key, "EDUCATION").sign(wording, citizen, now));

        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("samanvay-consent");
        assertThat(jwt.getHeader().getJWK().computeThumbprint()).isEqualTo(key.computeThumbprint());
        assertThat(jwt.getHeader().getJWK().isPrivate()).isFalse();
        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo("dept:EDUCATION");
        assertThat(c.getStringClaim("dept_code")).isEqualTo("EDUCATION");
        assertThat(c.getStringClaim("citizen_id")).isEqualTo(citizen.toString());
        assertThat(c.getStringClaim("request_id")).isEqualTo("r-1");
        assertThat(c.getStringClaim("purpose")).isEqualTo("DEMO_PURPOSE");
        assertThat(c.getStringClaim("nonce")).isEqualTo("n-1");
        assertThat(c.getStringListClaim("categories")).containsExactly("INCOME_CERTIFICATE");
        assertThat(c.getStringClaim("method")).isEqualTo("dept-otp");
        assertThat(c.getJWTID()).isNotBlank();
        assertThat(Duration.between(c.getIssueTime().toInstant(), c.getExpirationTime().toInstant())).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void a_wording_without_a_request_id_or_nonce_is_refused_instead_of_signing_the_text_null() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k").generate();
        ConsentSigner signer = new ConsentSigner(key, "EDUCATION");
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        java.util.function.BiFunction<Object, Object, Map<String, Object>> wording = (id, nonce) -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("requestId", id);
            m.put("purposeCode", "DEMO_PURPOSE");
            m.put("nonce", nonce);
            m.put("categories", List.of("INCOME_CERTIFICATE"));
            return m;
        };
        for (Object bad : new Object[] {null, "", "   "}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> signer.sign(wording.apply(bad, "n-1"), UUID.randomUUID(), now))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> signer.sign(wording.apply("r-1", bad), UUID.randomUUID(), now))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nonce");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> signer.sign(wording.apply("r-1", "n-1"), null, now)).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> noCategories = wording.apply("r-1", "n-1");
        noCategories.remove("categories");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> signer.sign(noCategories, UUID.randomUUID(), now)).isInstanceOf(IllegalArgumentException.class);
    }
}
