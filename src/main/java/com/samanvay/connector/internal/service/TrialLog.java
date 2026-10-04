package com.samanvay.connector.internal.service;

import com.samanvay.connector.api.TrialHistory;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The last trial per connector, in the database (table {@code connector_trial}): it survives a restart and is the same on every instance. */
@Service
public class TrialLog implements TrialHistory {

    private final JdbcClient jdbc;
    private final Clock clock;

    TrialLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String connectorRef, String outcome) {
        jdbc.sql("INSERT INTO connector_trial (connector_ref, tried_at, outcome) VALUES (:ref, :at, :outcome)"
                        + " ON CONFLICT (connector_ref) DO UPDATE SET tried_at = EXCLUDED.tried_at, outcome = EXCLUDED.outcome")
                .param("ref", connectorRef).param("at", Timestamp.from(clock.instant())).param("outcome", outcome).update();
    }

    @Override
    public Optional<Trial> last(String connectorRef) {
        return jdbc.sql("SELECT tried_at, outcome FROM connector_trial WHERE connector_ref = :ref").param("ref", connectorRef)
                .query((rs, n) -> new Trial(rs.getTimestamp("tried_at").toInstant(), rs.getString("outcome"))).optional();
    }
}
