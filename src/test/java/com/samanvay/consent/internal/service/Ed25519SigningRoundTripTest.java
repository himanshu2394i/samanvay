package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.consent.api.UnsignedGrant;
import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SecretStore;
import com.samanvay.shared.SubjectRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Ed25519SigningRoundTripTest {

    @Test
    void signedGrantVerifiesAndTamperFails() {
        SecretStore secrets = new EnvSecretStore();
        CanonicalJson json = new CanonicalJson();
        GrantSigner signer = new GrantSigner(secrets, json);
        AccessGrantRepository grants = mock(AccessGrantRepository.class);
        ConsentArtifactRepository consents = mock(ConsentArtifactRepository.class);
        when(grants.markUsedIfUnused(any(), any())).thenReturn(1);
        ConsentArtifactEntity artifact = new ConsentArtifactEntity();
        artifact.setVersion(1);
        when(consents.findById(any())).thenReturn(Optional.of(artifact));
        Clock clock = Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC);
        Ed25519GrantVerifier verifier = new Ed25519GrantVerifier(secrets, grants, consents, json, clock);

        UnsignedGrant unsigned = sample(clock.instant());
        AccessGrant grant = new AccessGrant(
                unsigned.id(),
                unsigned.nonce(),
                unsigned.consentId(),
                unsigned.consentVersion(),
                unsigned.subject(),
                unsigned.requester(),
                unsigned.category(),
                unsigned.departmentCode(),
                unsigned.connectorRef(),
                unsigned.purpose(),
                unsigned.issuedAt(),
                unsigned.expiresAt(),
                signer.sign(unsigned));
        verifier.verifyOrThrow(grant, DataCategory.INCOME_CERTIFICATE, "rev-income@1");

        AccessGrant tampered = grant.withCategory(DataCategory.MARKS);
        assertThatThrownBy(() -> verifier.verifyOrThrow(tampered, DataCategory.MARKS, "rev-income@1"))
                .isInstanceOf(InvalidGrantException.class);

        when(grants.markUsedIfUnused(eq(grant.nonce()), any())).thenReturn(0);
        assertThatThrownBy(() -> verifier.verifyOrThrow(grant, DataCategory.INCOME_CERTIFICATE, "rev-income@1"))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("nonce");

        Clock late = Clock.fixed(Instant.parse("2026-09-13T12:02:00Z"), ZoneOffset.UTC);
        Ed25519GrantVerifier expired = new Ed25519GrantVerifier(secrets, grants, consents, json, late);
        assertThatThrownBy(() -> expired.verifyOrThrow(grant, DataCategory.INCOME_CERTIFICATE, "rev-income@1"))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("expired");

        assertThat(Ed25519GrantVerifier.fieldIsNeverPrivateKey()).isTrue();
    }

    private static UnsignedGrant sample(Instant now) {
        return new UnsignedGrant(
                UUID.randomUUID(),
                new byte[] {1, 2, 3, 4},
                UUID.randomUUID(),
                1,
                new SubjectRef(UUID.randomUUID()),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                now,
                now.plusSeconds(60));
    }
}
