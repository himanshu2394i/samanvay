package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SamanvayApplication.class)
class ScholarshipJourneyIT extends PostgresIntegrationTest {

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
    JdbcTemplate jdbc;

    @Test
    void scholarshipHappyPathDoesNotPersistDepartmentPayload() {
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
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-4471-88", new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "EDUCATION", "STUDENT", "STU-1001", new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "DBT", "DBT", "DBT-55", new com.samanvay.identity.api.AuthProof("idp"));
        var request = consents.request(new ConsentRequestDraft(
                citizen,
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                "Post-matric scholarship",
                List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT")));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));

        JourneyInstance instance = journeys.start(
                "POST_MATRIC_SCHOLARSHIP", citizen, JsonMapper.builder().build().createObjectNode());
        assertThat(instance.id()).isNotNull();

        ApplicationView view = awaitProjected(citizen);
        assertThat(view.status()).isIn("VERIFIED", "SUBMITTED", "PARTIALLY_VERIFIED");
        assertThat(tracking.steps(view.referenceNo())).isNotEmpty();

        String blob = jdbc.queryForObject(
                """
                SELECT string_agg(t::text, ' ')
                FROM (
                  SELECT identity_link.*::text AS t FROM identity_link
                  UNION ALL SELECT consent_artifact.*::text FROM consent_artifact
                  UNION ALL SELECT consent_access_grant.*::text FROM consent_access_grant
                  UNION ALL SELECT registry_pointer.*::text FROM registry_pointer
                  UNION ALL SELECT tracking_application.*::text FROM tracking_application
                  UNION ALL SELECT tracking_step.*::text FROM tracking_step
                  UNION ALL SELECT orchestration_instance.*::text FROM orchestration_instance
                  UNION ALL SELECT orchestration_step_state.*::text FROM orchestration_step_state
                ) s
                """,
                String.class);
        assertThat(blob).doesNotContain("INCOME-AMT-998877");
    }

    /** After-commit tracking listeners finish after {@code start()} returns. */
    private ApplicationView awaitProjected(UUID citizen) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            var apps = tracking.forCitizen(citizen, Pageable.ofSize(10));
            if (!apps.getContent().isEmpty()) {
                ApplicationView view = tracking.byReference(apps.getContent().getFirst().referenceNo());
                if (!tracking.steps(view.referenceNo()).isEmpty()) {
                    return view;
                }
            }
            Thread.sleep(50);
        }
        var apps = tracking.forCitizen(citizen, Pageable.ofSize(10));
        assertThat(apps.getContent()).isNotEmpty();
        return tracking.byReference(apps.getContent().getFirst().referenceNo());
    }
}
