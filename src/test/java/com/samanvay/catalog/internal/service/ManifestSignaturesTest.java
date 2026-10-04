package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.samanvay.shared.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A department signs the exact bytes of its manifest with a key only it holds; Samanvay pins that key's thumbprint once
 * an admin approves it. This is the pure check: is the signature on THESE bytes, fresh, by an EC P-256 key (whose
 * thumbprint it returns)?
 */
class ManifestSignaturesTest {

    static final byte[] BODY = "{\"manifestVersion\":2}".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    static final String AUD = "https://dept.example.gov";

    @Test
    void a_valid_signature_returns_the_thumbprint_of_the_signing_key() {
        ECKey key = ManifestSigningFixture.newKey();
        String header = ManifestSigningFixture.sign(BODY, key, NOW, AUD);
        assertThat(ManifestSignatures.verify(BODY, header, NOW, AUD)).contains(ManifestSigningFixture.thumbprint(key));
    }

    @Test
    void no_signature_header_means_unsigned_not_invalid() {
        assertThat(ManifestSignatures.verify(BODY, null, NOW, AUD)).isEmpty();
        assertThat(ManifestSignatures.verify(BODY, "  ", NOW, AUD)).isEmpty();
    }

    @Test
    void a_body_changed_after_signing_is_refused() {
        String header = ManifestSigningFixture.sign(BODY, ManifestSigningFixture.newKey(), NOW, AUD);
        byte[] tampered = "{\"manifestVersion\":3}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ManifestSignatures.verify(tampered, header, NOW, AUD)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("signature");
    }

    @Test
    void a_signature_made_by_a_different_key_than_the_one_it_carries_is_refused() {
        // Attacker signs with their own key but claims the department's public key in the header.
        ECKey department = ManifestSigningFixture.newKey();
        ECKey attacker = ManifestSigningFixture.newKey();
        String forged = ManifestSigningFixture.sign(BODY, attacker, NOW, AUD);
        String[] parts = forged.split("\\.");
        String departmentHeader = ManifestSigningFixture.sign(BODY, department, NOW, AUD).split("\\.")[0];
        String spliced = departmentHeader + "." + parts[1] + "." + parts[2];
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, spliced, NOW, AUD)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void an_old_signature_is_refused_so_a_stale_manifest_cannot_be_replayed() {
        ECKey key = ManifestSigningFixture.newKey();
        String old = ManifestSigningFixture.sign(BODY, key, NOW.minusSeconds(11 * 60), AUD);
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, old, NOW, AUD)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("old");
        String fine = ManifestSigningFixture.sign(BODY, key, NOW.minusSeconds(9 * 60), AUD);
        assertThat(ManifestSignatures.verify(BODY, fine, NOW, AUD)).isPresent();
    }

    @Test
    void a_signature_dated_in_the_future_is_refused() {
        String future = ManifestSigningFixture.sign(BODY, ManifestSigningFixture.newKey(), NOW.plusSeconds(11 * 60), AUD);
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, future, NOW, AUD)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void garbage_and_non_es256_signatures_are_refused() throws Exception {
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, "not-a-jws", NOW, AUD)).isInstanceOf(InvalidRequestException.class);
        JWSObject hmac = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload("{\"sha256\":\"x\",\"iat\":1}"));
        hmac.sign(new MACSigner(new byte[32]));
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, hmac.serialize(), NOW, AUD)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void a_signature_made_for_another_host_is_refused_so_it_cannot_be_replayed_from_there() {
        ECKey key = ManifestSigningFixture.newKey();
        String forOther = ManifestSigningFixture.sign(BODY, key, NOW, "https://other.example.gov");
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, forOther, NOW, AUD)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("other.example.gov").hasMessageContaining("dept.example.gov");
        // same host, different port or scheme is a different origin
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, ManifestSigningFixture.sign(BODY, key, NOW, "https://dept.example.gov:8443"), NOW, AUD))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, ManifestSigningFixture.sign(BODY, key, NOW, "http://dept.example.gov"), NOW, AUD))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void a_signature_with_no_audience_is_refused() {
        String noAud = ManifestSigningFixture.sign(BODY, ManifestSigningFixture.newKey(), NOW, null);
        assertThatThrownBy(() -> ManifestSignatures.verify(BODY, noAud, NOW, AUD)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("audience");
    }

    @Test
    void the_audience_is_compared_as_an_origin_so_case_a_trailing_slash_and_a_default_port_do_not_matter() {
        ECKey key = ManifestSigningFixture.newKey();
        for (String aud : new String[] {"https://DEPT.example.gov", "https://dept.example.gov/", "https://dept.example.gov:443"}) {
            assertThat(ManifestSignatures.verify(BODY, ManifestSigningFixture.sign(BODY, key, NOW, aud), NOW, AUD)).as(aud).isPresent();
        }
    }
}
