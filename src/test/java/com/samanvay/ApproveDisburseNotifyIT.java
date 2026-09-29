package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.payments.api.Disbursement;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * The flagship "humans dispose" chain, proven across three modules at once: an OFFICER approves a
 * VERIFIED application over HTTP, orchestration publishes {@code ApplicationStateChanged(APPROVED)},
 * and that one event drives both payments (a mock-DBT disbursement with its instalments, issued
 * exactly once) and notifications (a delivery row recorded for the state change). Each module is
 * unit- and IT-tested on its own (OfficerApprovalIT, DisbursementIT, the dispatcher tests); this is
 * the only test that asserts the vertical slice end to end, including that a redelivery/re-approval
 * stays idempotent and never issues a second disbursement.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApproveDisburseNotifyIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DisbursementService disbursements;

    @Test
    void approvingVerifiedApplicationDisbursesAndNotifiesAndStaysIdempotent() throws Exception {
        UUID app = application("VERIFIED");
        // Subscribe the instance so the ApplicationStateChanged(APPROVED) fan-out records a delivery.
        // IN_APP needs no SMTP, so the row is recorded deterministically regardless of channel send.
        subscribeToStateChanges(app);

        // The officer disposes.
        assertThat(approve(TestTokens.officer("officer-adn-1"), app)).isEqualTo(200);

        // (a) orchestration moved VERIFIED -> APPROVED.
        assertThat(orchestrationStatus(app)).isEqualTo("APPROVED");

        // (b) payments issued the disbursement with its instalments, driven by the approval event.
        Optional<Disbursement> d = awaitDisbursement(app);
        assertThat(d).as("the approval event drove the disbursement listener").isPresent();
        assertThat(d.get().journeyCode()).isEqualTo("SOME_JOURNEY");
        assertThat(d.get().instalments()).isNotEmpty();
        assertThat(disbursementCount(app)).isEqualTo(1);

        // (c) notifications recorded a delivery for the APPROVED state change.
        assertThat(awaitStateChangeDelivery(app))
                .as("notifications recorded a delivery for the APPROVED state change")
                .isTrue();

        // Re-approval is a no-op: still 200, and neither a second disbursement nor its instalments appear.
        assertThat(approve(TestTokens.officer("officer-adn-2"), app)).isEqualTo(200);
        Thread.sleep(500);
        assertThat(disbursementCount(app)).as("re-approval does not issue a second disbursement").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments_instalment WHERE disbursement_id = ?", Integer.class, d.get().id()))
                .isEqualTo(d.get().instalments().size());
    }

    private int approve(String token, UUID app) {
        RestClient http = token == null ? TestHttp.anonymous() : TestHttp.as(token);
        return http.post()
                .uri("http://localhost:" + port + "/api/journeys/instances/" + app + "/approve")
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    /** An orchestration instance plus its tracking projection, both at {@code status}. */
    private UUID application(String status) {
        UUID id = UUID.randomUUID();
        UUID citizen = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orchestration_instance (id, journey_code, citizen_id, process_instance_id, status)"
                        + " VALUES (?, 'SOME_JOURNEY', ?, ?, ?)",
                id, citizen, "pi-" + id, status);
        jdbc.update(
                "INSERT INTO tracking_application (id, reference_no, citizen_id, journey_code, process_instance_id, status)"
                        + " VALUES (?, ?, ?, 'SOME_JOURNEY', ?, ?)",
                id, "MH-ADN-" + id.toString().substring(0, 20), citizen, "pi-" + id, status);
        return id;
    }

    private void subscribeToStateChanges(UUID app) {
        jdbc.update(
                "INSERT INTO notification_subscription (id, recipient_id, event_type, channel, locale, enabled)"
                        + " VALUES (?, ?, 'ApplicationStateChanged', 'IN_APP', 'en', true)",
                UUID.randomUUID(), app.toString());
    }

    private String orchestrationStatus(UUID app) {
        return jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, app);
    }

    private int disbursementCount(UUID app) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments_disbursement WHERE application_id = ?", Integer.class, app);
    }

    private Optional<Disbursement> awaitDisbursement(UUID app) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<Disbursement> d = disbursements.forApplication(app);
            if (d.isPresent()) {
                return d;
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    private boolean awaitStateChangeDelivery(UUID app) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM notification_delivery"
                            + " WHERE recipient_id = ? AND event_type = 'ApplicationStateChanged' AND dedupe_key = ?",
                    Integer.class, app.toString(), app + ":APPROVED");
            if (rows != null && rows > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
