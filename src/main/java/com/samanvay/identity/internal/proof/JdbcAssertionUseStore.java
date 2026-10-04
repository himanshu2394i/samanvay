package com.samanvay.identity.internal.proof;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link AssertionUseStore} in {@code identity_assertion_use}; the primary key makes {@link #firstUse} atomic. */
@Component
class JdbcAssertionUseStore implements AssertionUseStore {

    private static final Duration KEEP_EXPIRED = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcAssertionUseStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean firstUse(String departmentCode, String jti, Instant expiresAt) {
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM identity_assertion_use WHERE expires_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(KEEP_EXPIRED))).update();
        return jdbc.sql("INSERT INTO identity_assertion_use (department_code, jti, used_at, expires_at)"
                        + " VALUES (:dept, :jti, :now, :expires) ON CONFLICT DO NOTHING")
                .param("dept", departmentCode).param("jti", jti)
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expiresAt)).update() == 1;
    }
}
