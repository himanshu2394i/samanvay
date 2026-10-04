package com.samanvay.tracking.internal.service;

import com.samanvay.tracking.api.SlaOverview;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class TrackingSlaOverview implements SlaOverview {

    private static final String OPEN = "status NOT IN ('APPROVED','REJECTED','CLOSED') AND sla_due_at IS NOT NULL"
            // a due time at or before submission means "no SLA" (a journey with 0 hours), never a born-breached case
            + " AND sla_due_at > submitted_at";

    private final JdbcTemplate jdbc;

    TrackingSlaOverview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot(Instant asOf) {
        Timestamp now = Timestamp.from(asOf);
        Timestamp soon = Timestamp.from(asOf.plus(DUE_SOON));
        List<JourneySla> byJourney = jdbc.query(
                """
                SELECT journey_code,
                       count(*) AS open,
                       count(*) FILTER (WHERE sla_due_at < ?) AS breached,
                       count(*) FILTER (WHERE sla_due_at >= ? AND sla_due_at < ?) AS due_soon
                FROM tracking_application
                WHERE %s
                GROUP BY journey_code ORDER BY journey_code
                """.formatted(OPEN),
                (rs, n) -> new JourneySla(
                        rs.getString("journey_code"), rs.getLong("open"), rs.getLong("breached"), rs.getLong("due_soon")),
                now,
                now,
                soon);
        List<Case> watchlist = jdbc.query(
                """
                SELECT reference_no, journey_code, status, sla_due_at
                FROM tracking_application
                WHERE %s
                ORDER BY sla_due_at ASC, reference_no ASC
                LIMIT ?
                """.formatted(OPEN),
                (rs, n) -> {
                    Instant due = rs.getTimestamp("sla_due_at").toInstant();
                    return new Case(
                            rs.getString("reference_no"),
                            rs.getString("journey_code"),
                            rs.getString("status"),
                            due,
                            Duration.between(asOf, due).toSeconds());
                },
                WATCHLIST_SIZE);
        return new Snapshot(
                asOf,
                byJourney.stream().mapToLong(JourneySla::open).sum(),
                byJourney.stream().mapToLong(JourneySla::breached).sum(),
                byJourney.stream().mapToLong(JourneySla::dueSoon).sum(),
                byJourney,
                watchlist);
    }
}
