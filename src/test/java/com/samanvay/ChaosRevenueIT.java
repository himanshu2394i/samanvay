package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SamanvayApplication.class)
@ActiveProfiles("demo")
class ChaosRevenueIT extends PostgresIntegrationTest {

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

    @AfterEach
    void revive() {
        chaos.revive("revenue-rest-mock");
    }

    @Test
    void revenueKilledMidFlightLeavesPartialAndRetryRecovers() throws InterruptedException {
        UUID citizen = seedScholarshipCitizen();
        chaos.kill("revenue-rest-mock");
        JourneyInstance instance = journeys.start(
                "POST_MATRIC_SCHOLARSHIP", citizen, JsonMapper.builder().build().createObjectNode());
        ApplicationView down = awaitStatus(citizen, "PARTIALLY_VERIFIED");
        assertThat(down.status()).isEqualTo("PARTIALLY_VERIFIED");
        assertThat(journeys.openExceptions()).extracting(JourneyExceptionView::instanceId).contains(instance.id());

        chaos.revive("revenue-rest-mock");
        journeys.retryPending(instance.id());
        ApplicationView recovered = awaitStatus(citizen, "VERIFIED");
        assertThat(recovered.status()).isEqualTo("VERIFIED");
        assertThat(journeys.openExceptions().stream().noneMatch(e -> e.instanceId().equals(instance.id()))).isTrue();
    }

    private UUID seedScholarshipCitizen() {
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
        String suffix = Long.toHexString(System.nanoTime());
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-ch-" + suffix, new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "EDUCATION", "STUDENT", "STU-ch-" + suffix, new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "DBT", "DBT", "DBT-ch-" + suffix, new com.samanvay.identity.api.AuthProof("idp"));
        var request = consents.request(new ConsentRequestDraft(
                citizen,
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                "Post-matric scholarship",
                List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT")));
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
}
