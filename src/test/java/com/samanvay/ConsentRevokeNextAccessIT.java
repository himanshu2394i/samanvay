package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class ConsentRevokeNextAccessIT extends PostgresIntegrationTest {

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    @Autowired
    AccessAuthority access;

    @Test
    void revokeDeniesNextAuthorizeWithinGrantTtl() throws InterruptedException {
        UUID citizen = profiles.register(new ProfileDraft(
                "Ramesh Kumar",
                "रमेश",
                "Ramesh",
                "Kumar",
                "Suresh",
                LocalDate.of(2004, 1, 15),
                "DAY",
                "M",
                "99****21"));
        linking.assertLink(
                citizen,
                "REVENUE",
                "RATION",
                "RC-rv-" + Long.toHexString(System.nanoTime()),
                com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        var request = consents.request(new ConsentRequestDraft(
                citizen,
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                "t",
                List.of("INCOME_CERTIFICATE")));
        var artifact = consents.grant(request.id(), citizen, new AuthProof("session-jti"));
        AccessRequest accessRequest = new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                "POST_MATRIC_SCHOLARSHIP");
        AccessDecision first = awaitGranted(accessRequest);
        assertThat(first).isInstanceOf(AccessDecision.Granted.class);
        consents.revoke(artifact.id(), citizen, "changed mind");
        AccessDecision second = access.authorize(accessRequest);
        assertThat(second).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) second).reason())
                .isIn(DenialReason.CONSENT_REVOKED, DenialReason.NO_CONSENT);
    }

    private AccessDecision awaitGranted(AccessRequest request) throws InterruptedException {
        AccessDecision last = null;
        for (int i = 0; i < 80; i++) {
            last = access.authorize(request);
            if (last instanceof AccessDecision.Granted) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }
}
