package com.samanvay.orchestration.internal.service;

import com.samanvay.shared.OpsMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gauges over the orchestration exception queue (open rows in {@code orchestration_exception}):
 * current depth and the age of the oldest open row. Read-only. Both gauges share one aggregate
 * query, cached for a few seconds so a dashboard refresh costs at most one query per interval.
 * When the database cannot be read the gauges report NaN rather than a stale number.
 */
@Component
class ExceptionQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(ExceptionQueueMetrics.class);
    static final Duration CACHE_FOR = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private Snapshot cached;
    private Instant cachedAt = Instant.MIN;

    ExceptionQueueMetrics(JdbcTemplate jdbc, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder(OpsMetrics.EXCEPTIONS_OPEN, this, m -> m.snapshot().open())
                .description("Open rows in the orchestration exception queue")
                .register(meters);
        Gauge.builder(OpsMetrics.EXCEPTIONS_OLDEST_AGE, this, m -> m.snapshot().oldestAgeSeconds())
                .description("Age of the oldest open orchestration exception, 0 when the queue is empty")
                .baseUnit("seconds")
                .register(meters);
    }

    private synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached == null || Duration.between(cachedAt, now).compareTo(CACHE_FOR) >= 0) {
            cached = read(now);
            cachedAt = now;
        }
        return cached;
    }

    private Snapshot read(Instant now) {
        try {
            return jdbc.query(
                    "SELECT count(*) AS open, min(created_at) AS oldest FROM orchestration_exception WHERE status = 'OPEN'",
                    rs -> {
                        rs.next();
                        long open = rs.getLong("open");
                        Timestamp oldest = rs.getTimestamp("oldest");
                        double age = oldest == null
                                ? 0
                                : Math.max(0, Duration.between(oldest.toInstant(), now).toMillis() / 1000.0);
                        return new Snapshot(open, age);
                    });
        } catch (RuntimeException e) {
            log.warn("exception-queue gauges unavailable: {}", e.toString());
            return new Snapshot(Double.NaN, Double.NaN);
        }
    }

    private record Snapshot(double open, double oldestAgeSeconds) {}
}
