package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.internal.service.DemoAuditTamper;
import com.samanvay.catalog.api.ImportPreview;
import com.samanvay.catalog.api.SpecImport;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SamanvayApplication.class)
class DemoRehearsalIT extends PostgresIntegrationTest {

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    @Autowired
    JourneyService journeys;

    @Autowired
    ApplicationTracking tracking;

    @Autowired
    DepartmentChaos chaos;

    @Autowired
    AccessAuthority access;

    @Autowired
    AuditService audit;

    @Autowired
    DemoAuditTamper tamper;

    @Autowired
    SpecImport importer;

    @AfterEach
    void revive() {
        chaos.revive("revenue-rest-mock");
    }

    @RepeatedTest(3)
    void eightBeatsUnattended() throws InterruptedException {
        ApplicationView scholarship = startJourney(
                "POST_MATRIC_SCHOLARSHIP",
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT"),
                List.of(
                        new String[] {"REVENUE", "RATION", "RC-4471-88"},
                        new String[] {"EDUCATION", "STUDENT", "STU-1001"},
                        new String[] {"DBT", "DBT", "DBT-55"}));
        ApplicationView noc = startJourney(
                "BUSINESS_NOC",
                "INDUSTRY",
                "BUSINESS_NOC",
                List.of("PROPERTY", "FIRE_NOC", "POLLUTION_CLEARANCE", "LAND_RECORD"),
                List.of(
                        new String[] {"MUNICIPAL", "PROPERTY", "PROP-1"},
                        new String[] {"FIRE", "PREMISE", "FIRE-1"},
                        new String[] {"POLLUTION", "PREMISE", "PCB-1"},
                        new String[] {"REVENUE", "RATION", "RC-NOC-1"}));
        ApplicationView farmer = startJourney(
                "FARMER_SUBSIDY",
                "AGRICULTURE",
                "FARMER_SUBSIDY",
                List.of("LAND_PARCEL", "CROP_RECORD", "BANK_ACCOUNT"),
                List.of(
                        new String[] {"REVENUE", "RATION", "RC-712-1"},
                        new String[] {"AGRICULTURE", "FARMER", "AGR-1"},
                        new String[] {"DBT", "DBT", "DBT-712"}));
        assertThat(List.of(scholarship.status(), noc.status(), farmer.status()))
                .allMatch(s -> List.of("VERIFIED", "SUBMITTED", "PARTIALLY_VERIFIED").contains(s));

        chaos.kill("revenue-rest-mock");
        UUID chaosCitizen = seed(
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT"),
                List.of(
                        new String[] {"REVENUE", "RATION", "RC-4471-88"},
                        new String[] {"EDUCATION", "STUDENT", "STU-1001"},
                        new String[] {"DBT", "DBT", "DBT-55"}));
        JourneyInstance down = journeys.start(
                "POST_MATRIC_SCHOLARSHIP", chaosCitizen, JsonMapper.builder().build().createObjectNode());
        assertThat(awaitProjected(chaosCitizen).status()).isEqualTo("PARTIALLY_VERIFIED");
        chaos.revive("revenue-rest-mock");
        journeys.retryPending(down.id());
        assertThat(awaitStatus(chaosCitizen, "VERIFIED").status()).isEqualTo("VERIFIED");

        UUID revokeCitizen = seed(
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                List.of("INCOME_CERTIFICATE"),
                List.of(new String[] {"REVENUE", "RATION", "RC-4471-88"}));
        var artifact = consents.forCitizen(revokeCitizen).getFirst();
        AccessRequest req = new AccessRequest(
                new SubjectRef(revokeCitizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                "POST_MATRIC_SCHOLARSHIP");
        assertThat(awaitGranted(req)).isInstanceOf(AccessDecision.Granted.class);
        consents.revoke(artifact.id(), revokeCitizen, "demo");
        assertThat(access.authorize(req)).isInstanceOf(AccessDecision.Denied.class);

        audit.record(new AuditEntry(
                ActorType.SYSTEM, "rehearsal", "PING", "demo", null, null, null, null, Outcome.ALLOWED, null, Map.of()));
        long head = audit.headSeq();
        assertThat(audit.verify(head, head).valid()).isTrue();
        tamper.rewriteReason(head, "tampered-demo");
        assertThat(audit.verify(head, head).valid()).isFalse();

        ImportPreview preview = importer.preview(
                """
                {"openapi":"3.0.0","paths":{"/income":{"get":{"operationId":"getIncome","responses":{"200":{"content":{"application/json":{"schema":{"properties":{"annual_income":{"type":"number"}}}}}}}}}}}
                """,
                "getIncome",
                "Credential/IncomeCertificate@1");
        assertThat(preview.suggestions()).isNotEmpty();
        assertThat(preview.suggestions()).allMatch(s -> !s.approved());
    }

    private ApplicationView startJourney(
            String journey, String requester, String purpose, List<String> cats, List<String[]> links)
            throws InterruptedException {
        UUID citizen = seed(requester, purpose, cats, links);
        journeys.start(journey, citizen, JsonMapper.builder().build().createObjectNode());
        return awaitProjected(citizen);
    }

    private UUID seed(String requester, String purpose, List<String> cats, List<String[]> links) {
        UUID citizen = profiles.register(new ProfileDraft(
                "Demo", "डेमो", "Demo", "User", "X", LocalDate.of(2000, 1, 1), "DAY", "M", "11****11"));
        for (String[] link : links) {
            linking.assertLink(citizen, link[0], link[1], link[2], new com.samanvay.identity.api.AuthProof("idp"));
        }
        var request = consents.request(new ConsentRequestDraft(citizen, requester, purpose, purpose, cats));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));
        return citizen;
    }

    private ApplicationView awaitProjected(UUID citizen) throws InterruptedException {
        return awaitStatus(citizen, null);
    }

    private ApplicationView awaitStatus(UUID citizen, String expected) throws InterruptedException {
        ApplicationView last = null;
        for (int i = 0; i < 80; i++) {
            var apps = tracking.forCitizen(citizen, Pageable.ofSize(10));
            if (!apps.getContent().isEmpty()) {
                last = tracking.byReference(apps.getContent().getFirst().referenceNo());
                if (!tracking.steps(last.referenceNo()).isEmpty()
                        && (expected == null || expected.equals(last.status()))) {
                    return last;
                }
            }
            Thread.sleep(50);
        }
        assertThat(last).isNotNull();
        return last;
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
