package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.consent.api.UnsignedGrant;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class ConsentVersionRevocationIT extends PostgresIntegrationTest {

    @Autowired
    ConsentService consents;

    @Autowired
    GrantSigner signer;

    @Autowired
    AccessGrantVerifier verifier;

    @Test
    void revokedConsentRejectsOldGrant() {
        UUID citizen = UUID.randomUUID();
        var req = consents.request(new ConsentRequestDraft(
                citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY", "t", List.of("INCOME_CERTIFICATE")));
        var artifact = consents.grant(req.id(), citizen, new AuthProof("jti"));
        UnsignedGrant unsigned = new UnsignedGrant(
                UUID.randomUUID(),
                new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2},
                artifact.id(),
                artifact.version(),
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                Instant.now(),
                Instant.now().plusSeconds(60));
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
        consents.revoke(artifact.id(), citizen, "changed mind");
        assertThatThrownBy(() -> verifier.verifyOrThrow(grant, DataCategory.INCOME_CERTIFICATE, "rev-income@1"))
                .isInstanceOf(InvalidGrantException.class);
    }
}
