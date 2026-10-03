package com.samanvay.identity.internal.proof;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Department login states in the database (table {@code identity_dept_login_state}). {@link #consume} is one atomic
 * conditional UPDATE, so of any number of concurrent attempts exactly one wins; expiry is judged by the injected clock.
 */
@Component
public class JdbcDepartmentLoginStates implements DepartmentLoginStates {

    private static final Duration KEEP_EXPIRED = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    JdbcDepartmentLoginStates(
            JdbcClient jdbc, Clock clock, @Value("${samanvay.identity.department-assertion.login-ttl:PT10M}") String ttl) {
        this(jdbc, clock, Duration.parse(ttl.trim()));
    }

    public JdbcDepartmentLoginStates(JdbcClient jdbc, Clock clock, Duration ttl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public LoginState issue(UUID citizenId, String departmentCode) {
        Instant now = clock.instant();
        // Housekeeping on the write path: rows long past their expiry are of no further use.
        jdbc.sql("DELETE FROM identity_dept_login_state WHERE expires_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(KEEP_EXPIRED))).update();
        LoginState s = new LoginState(token(), token());
        jdbc.sql("INSERT INTO identity_dept_login_state (state, nonce, citizen_id, department_code, created_at, expires_at)"
                        + " VALUES (:state, :nonce, :citizen, :dept, :now, :expires)")
                .param("state", s.state()).param("nonce", s.nonce()).param("citizen", citizenId).param("dept", departmentCode)
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(ttl))).update();
        return s;
    }

    @Override
    public boolean consume(UUID citizenId, String departmentCode, String state, String nonce) {
        if (citizenId == null || isBlank(departmentCode) || isBlank(state) || isBlank(nonce)) {
            return false;
        }
        Instant now = clock.instant();
        int updated = jdbc.sql("UPDATE identity_dept_login_state SET consumed_at = :now"
                        + " WHERE state = :state AND nonce = :nonce AND citizen_id = :citizen AND department_code = :dept"
                        + " AND consumed_at IS NULL AND expires_at > :now")
                .param("now", Timestamp.from(now)).param("state", state).param("nonce", nonce)
                .param("citizen", citizenId).param("dept", departmentCode).update();
        return updated == 1;
    }

    private String token() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
