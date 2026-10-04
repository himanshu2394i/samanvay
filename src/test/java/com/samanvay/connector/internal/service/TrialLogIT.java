package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The last trial of a connector is kept in the database, so it survives a restart and is the same on every instance. */
@SpringBootTest(classes = SamanvayApplication.class)
class TrialLogIT extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void a_recorded_trial_is_still_there_for_a_new_instance_which_is_what_a_restart_is() {
        String ref = "trial-log-it-" + System.nanoTime() + "@1";
        new TrialLog(jdbc, Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC)).record(ref, "NOT_FOUND");

        var restarted = new TrialLog(jdbc, Clock.systemUTC());
        assertThat(restarted.last(ref)).hasValueSatisfying(t -> {
            assertThat(t.outcome()).isEqualTo("NOT_FOUND");
            assertThat(t.at()).isEqualTo(Instant.parse("2026-10-04T10:00:00Z"));
        });
    }

    @Test
    void the_next_trial_replaces_the_last_and_an_untried_connector_has_none() {
        String ref = "trial-log-it-" + System.nanoTime() + "@2";
        TrialLog log = new TrialLog(jdbc, Clock.systemUTC());
        assertThat(log.last(ref)).isEmpty();
        log.record(ref, "UNAVAILABLE");
        log.record(ref, "SUCCESS");
        assertThat(log.last(ref)).hasValueSatisfying(t -> assertThat(t.outcome()).isEqualTo("SUCCESS"));
    }
}
