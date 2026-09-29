package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
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
 * The officer approval step end to end on real Postgres: VERIFIED -> APPROVED over HTTP, the
 * ApplicationStateChanged event reaching payments (a disbursement appears, tracking shows APPROVED),
 * the audit row, idempotency, and the OFFICER-only guard.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OfficerApprovalIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DisbursementService disbursements;

    @Test
    void approvingAVerifiedApplicationDisbursesOnceAndIsIdempotent() throws Exception {
        UUID app = application("VERIFIED");

        assertThat(approve(TestTokens.officer("officer-approve-1"), app)).isEqualTo(200);

        assertThat(orchestrationStatus(app)).isEqualTo("APPROVED");
        Optional<Disbursement> d = awaitDisbursement(app);
        assertThat(d).as("the approval event drove the disbursement listener").isPresent();
        assertThat(d.get().journeyCode()).isEqualTo("SOME_JOURNEY");
        assertThat(d.get().instalments()).isNotEmpty();
        assertThat(awaitTrackingStatus(app)).isEqualTo("APPROVED");
        assertThat(auditCount(app)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_id FROM audit.audit_entry WHERE action = 'APPLICATION_APPROVED' AND meta->>'applicationId' = ?",
                        String.class, app.toString()))
                .isEqualTo("officer-approve-1");

        // A repeat is a no-op: still 200, no second audit row, still one disbursement.
        assertThat(approve(TestTokens.officer("officer-approve-2"), app)).isEqualTo(200);
        Thread.sleep(500);
        assertThat(auditCount(app)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments_disbursement WHERE application_id = ?", Integer.class, app))
                .isEqualTo(1);
    }

    @Test
    void onlyOfficersMayApprove() {
        UUID app = application("VERIFIED");
        assertThat(approve(TestTokens.citizen("citizen-" + UUID.randomUUID()), app)).isEqualTo(403);
        assertThat(approve(TestTokens.reviewer("reviewer-1"), app)).isEqualTo(403);
        assertThat(approve(TestTokens.admin("admin-1"), app)).isEqualTo(403);
        assertThat(approve(null, app)).isEqualTo(401);
        assertThat(orchestrationStatus(app)).isEqualTo("VERIFIED");
        assertThat(auditCount(app)).isZero();
    }

    @Test
    void onlyAVerifiedApplicationCanBeApproved() {
        for (String status : new String[] {"PARTIALLY_VERIFIED", "REJECTED", "CLOSED"}) {
            UUID app = application(status);
            assertThat(approve(TestTokens.officer("officer-approve-3"), app)).as(status).isEqualTo(409);
            assertThat(orchestrationStatus(app)).isEqualTo(status);
            assertThat(auditCount(app)).isZero();
        }
        assertThat(approve(TestTokens.officer("officer-approve-3"), UUID.randomUUID())).isEqualTo(404);
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
                id, "MH-APR-" + id.toString().substring(0, 20), citizen, "pi-" + id, status);
        return id;
    }

    private String orchestrationStatus(UUID app) {
        return jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, app);
    }

    private int auditCount(UUID app) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_entry WHERE action = 'APPLICATION_APPROVED' AND meta->>'applicationId' = ?",
                Integer.class, app.toString());
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

    private String awaitTrackingStatus(UUID app) throws InterruptedException {
        String status = null;
        for (int i = 0; i < 100; i++) {
            status = jdbc.queryForObject("SELECT status FROM tracking_application WHERE id = ?", String.class, app);
            if ("APPROVED".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        return status;
    }
}
