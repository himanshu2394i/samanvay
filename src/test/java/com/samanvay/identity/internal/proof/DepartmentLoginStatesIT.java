package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The login a citizen starts at a department is single-use, short-lived and bound to that citizen and department. */
@SpringBootTest(classes = SamanvayApplication.class)
class DepartmentLoginStatesIT extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    JdbcDepartmentLoginStates at(Instant now) {
        return new JdbcDepartmentLoginStates(jdbc, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(10));
    }

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void an_issued_login_can_be_consumed_exactly_once() {
        var states = at(T0);
        UUID citizen = UUID.randomUUID();
        var s = states.issue(citizen, "REVENUE");
        assertThat(states.consume(citizen, "REVENUE", s.state(), s.nonce())).isTrue();
        assertThat(states.consume(citizen, "REVENUE", s.state(), s.nonce())).isFalse();
    }

    @Test
    void state_and_nonce_are_unguessable_and_different_every_time() {
        var states = at(T0);
        var a = states.issue(UUID.randomUUID(), "REVENUE");
        var b = states.issue(UUID.randomUUID(), "REVENUE");
        assertThat(a.state()).hasSizeGreaterThanOrEqualTo(32).isNotEqualTo(b.state());
        assertThat(a.nonce()).hasSizeGreaterThanOrEqualTo(32).isNotEqualTo(b.nonce()).isNotEqualTo(a.state());
    }

    @Test
    void another_citizen_another_department_or_a_wrong_value_cannot_consume_it_and_does_not_burn_it() {
        var states = at(T0);
        UUID citizen = UUID.randomUUID();
        var s = states.issue(citizen, "REVENUE");
        assertThat(states.consume(UUID.randomUUID(), "REVENUE", s.state(), s.nonce())).isFalse();
        assertThat(states.consume(citizen, "DBT", s.state(), s.nonce())).isFalse();
        assertThat(states.consume(citizen, "REVENUE", s.state(), "wrong-nonce")).isFalse();
        assertThat(states.consume(citizen, "REVENUE", "wrong-state", s.nonce())).isFalse();
        assertThat(states.consume(citizen, "REVENUE", s.state(), s.nonce())).isTrue();
    }

    @Test
    void a_login_expires_after_the_ttl() {
        UUID citizen = UUID.randomUUID();
        var s = at(T0).issue(citizen, "REVENUE");
        assertThat(at(T0.plus(Duration.ofMinutes(11))).consume(citizen, "REVENUE", s.state(), s.nonce())).isFalse();
        assertThat(at(T0.plus(Duration.ofMinutes(9))).consume(citizen, "REVENUE", s.state(), s.nonce())).isTrue();
    }

    @Test
    void null_or_blank_values_never_match() {
        var states = at(T0);
        UUID citizen = UUID.randomUUID();
        states.issue(citizen, "REVENUE");
        assertThat(states.consume(citizen, "REVENUE", null, null)).isFalse();
        assertThat(states.consume(null, "REVENUE", "x", "y")).isFalse();
        assertThat(states.consume(citizen, null, "x", "y")).isFalse();
        assertThat(states.consume(citizen, "REVENUE", "", "")).isFalse();
    }

    @Test
    void concurrent_attempts_to_consume_the_same_login_let_exactly_one_through() throws Exception {
        var states = at(T0);
        UUID citizen = UUID.randomUUID();
        var s = states.issue(citizen, "REVENUE");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> states.consume(citizen, "REVENUE", s.state(), s.nonce()));
            }
            int winners = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                winners += f.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
