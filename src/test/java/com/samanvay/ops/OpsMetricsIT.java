package com.samanvay.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ops dashboards over a real database: SLA rows (due-at vs now on {@code tracking_application})
 * and the exception queue (gauges over {@code orchestration_exception}). Runs in CI (needs Docker).
 * The database is shared with other ITs, so every assertion is about rows this test created.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsMetricsIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    private Map<String, Object> metrics() {
        return TestHttp.as(TestTokens.officer("ops-it-officer"))
                .get()
                .uri("http://localhost:" + port + "/api/ops/metrics")
                .retrieve()
                .body(Map.class);
    }

    @Test
    void slaDashboardComparesDueAtWithNowOnOpenApplicationsOnly() {
        String journey = "OBS_IT_" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        app(journey, "SUBMITTED", now.minusSeconds(7200)); // breached
        app(journey, "PARTIALLY_VERIFIED", now.plusSeconds(3600)); // due soon
        app(journey, "SUBMITTED", now.plusSeconds(3 * 86_400)); // comfortably within SLA
        app(journey, "APPROVED", now.minusSeconds(86_400)); // finished late: no longer open, not a live breach

        Map<String, Object> sla = section(metrics(), "sla");
        Map<String, Object> row = byJourney(sla, journey);

        assertThat(row).containsEntry("open", 3).containsEntry("breached", 1).containsEntry("dueSoon", 1);
        assertThat(((Number) sla.get("open")).longValue()).isGreaterThanOrEqualTo(3);
        assertThat(sla).containsKey("watchlist");
        assertThat(sla.get("withinSlaPercent")).isNotNull();
    }

    @Test
    void exceptionQueueDashboardShowsDepthAgeAndReasons() {
        String reason = "OBS_IT_" + UUID.randomUUID().toString().substring(0, 8);
        long baseline = ((Number) section(metrics(), "exceptionQueue").get("open")).longValue();
        exception(reason, "OPEN", Instant.now().minusSeconds(7200));
        exception(reason, "OPEN", Instant.now().minusSeconds(60));
        exception(reason, "RESOLVED", Instant.now().minusSeconds(86_400));

        // the reason breakdown is read live
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byReason = (List<Map<String, Object>>) section(metrics(), "exceptionQueue").get("byReason");
        assertThat(byReason).anySatisfy(r -> assertThat(r).containsEntry("reason", reason).containsEntry("count", 2));

        // the depth gauge is cached for a few seconds
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            Map<String, Object> queue = section(metrics(), "exceptionQueue");
            assertThat(((Number) queue.get("open")).longValue()).isGreaterThanOrEqualTo(baseline + 2);
            assertThat(((Number) queue.get("oldestAgeSeconds")).doubleValue()).isGreaterThanOrEqualTo(7200.0);
        });
    }

    @Test
    void connectorAndConsentDashboardsAreAlwaysPresent() {
        Map<String, Object> body = metrics();

        assertThat(body).containsKeys("generatedAt", "connector", "sla", "consent", "exceptionQueue");
        assertThat(section(body, "connector")).containsKey("sources");
        assertThat(section(body, "consent")).containsKeys("granted", "denied", "denialsByReason");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> body, String name) {
        return (Map<String, Object>) body.get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byJourney(Map<String, Object> sla, String journey) {
        return ((List<Map<String, Object>>) sla.get("byJourney"))
                .stream()
                .filter(r -> journey.equals(r.get("journeyCode")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SLA row for " + journey));
    }

    private void app(String journey, String status, Instant slaDueAt) {
        jdbc.update(
                """
                INSERT INTO tracking_application
                  (id, reference_no, citizen_id, journey_code, process_instance_id, status, submitted_at, sla_due_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)
                """,
                UUID.randomUUID(),
                "OBS-" + UUID.randomUUID().toString().substring(0, 30),
                UUID.randomUUID(),
                journey,
                "proc-" + UUID.randomUUID(),
                status,
                Timestamp.from(slaDueAt));
    }

    private void exception(String reason, String status, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO orchestration_exception (id, instance_id, step_code, reason, status, created_at)
                VALUES (?, ?, 'INCOME', ?, ?, ?)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reason,
                status,
                Timestamp.from(createdAt));
    }
}
