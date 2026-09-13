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
class BusinessNocJourneyIT extends PostgresIntegrationTest {

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
    void businessNocHappyPathUsesSharedCore() throws InterruptedException {
        UUID citizen = profiles.register(new ProfileDraft(
                "Anita Desai",
                "अनिता",
                "Anita",
                "Desai",
                "Ravi",
                LocalDate.of(1988, 3, 4),
                "DAY",
                "F",
                "98****11"));
        linking.assertLink(citizen, "MUNICIPAL", "PROPERTY", "PROP-88", new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "FIRE", "PREMISE", "FIRE-1", new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "POLLUTION", "PREMISE", "PCB-1", new com.samanvay.identity.api.AuthProof("idp"));
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-NOC-1", new com.samanvay.identity.api.AuthProof("idp"));
        var request = consents.request(new ConsentRequestDraft(
                citizen,
                "INDUSTRY",
                "BUSINESS_NOC",
                "Business licence / NOC",
                List.of("PROPERTY", "FIRE_NOC", "POLLUTION_CLEARANCE", "LAND_RECORD")));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));

        JourneyInstance instance = journeys.start("BUSINESS_NOC", citizen, JsonMapper.builder().build().createObjectNode());
        assertThat(instance.id()).isNotNull();

        ApplicationView view = awaitProjected(citizen);
        assertThat(view.journeyCode()).isEqualTo("BUSINESS_NOC");
        assertThat(view.referenceNo()).contains("-NOC-");
        assertThat(view.status()).isIn("VERIFIED", "SUBMITTED", "PARTIALLY_VERIFIED");
        assertThat(tracking.steps(view.referenceNo())).hasSizeGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM orchestration_instance WHERE journey_code = 'BUSINESS_NOC'", Integer.class))
                .isGreaterThan(0);
    }

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
