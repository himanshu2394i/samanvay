package com.samanvay.tracking.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.SlaOverview;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** An application whose SLA is zero hours (due time equal to submission) has no SLA: it is never counted as breached. */
@SpringBootTest(classes = SamanvayApplication.class)
class TrackingSlaOverviewIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SlaOverview sla;

    @Test
    void a_zero_hour_sla_is_not_a_born_breached_case() {
        Instant submitted = Instant.now().minusSeconds(3600);
        insert("SLA_ZERO_IT", submitted, submitted); // legacy row: sla_hours 0 stored due == submitted
        insert("SLA_REAL_IT", submitted, submitted.plusSeconds(60)); // really overdue

        var snapshot = sla.snapshot(Instant.now());

        assertThat(snapshot.byJourney().stream().filter(j -> j.journeyCode().equals("SLA_ZERO_IT"))).isEmpty();
        assertThat(snapshot.byJourney().stream().filter(j -> j.journeyCode().equals("SLA_REAL_IT")).findFirst().orElseThrow().breached())
                .isEqualTo(1);
    }

    private void insert(String journey, Instant submitted, Instant due) {
        jdbc.update(
                "INSERT INTO tracking_application (id, reference_no, citizen_id, journey_code, process_instance_id, status, submitted_at, sla_due_at)"
                        + " VALUES (?, ?, ?, ?, 'p', 'SUBMITTED', ?, ?)",
                UUID.randomUUID(),
                "MH-SLA-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(),
                journey,
                java.sql.Timestamp.from(submitted),
                java.sql.Timestamp.from(due));
    }
}
