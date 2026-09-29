package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.samanvay.shared.OpsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Queue depth and oldest-age gauges over the exception queue table (H2 stands in for Postgres here). */
class ExceptionQueueMetricsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private JdbcTemplate jdbc;
    /** Gauges hold their source weakly (in the app the bean keeps it alive), so the test must too. */
    private ExceptionQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:exq" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE orchestration_exception (
                    id UUID PRIMARY KEY, instance_id UUID NOT NULL, step_code VARCHAR(60) NOT NULL,
                    reason VARCHAR(200), status VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        metrics = new ExceptionQueueMetrics(jdbc, meters, clock);
    }

    private void insert(String status, Instant createdAt) {
        jdbc.update(
                "INSERT INTO orchestration_exception VALUES (?, ?, 'INCOME', 'FAILED', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), status, Timestamp.from(createdAt));
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    void emptyQueueHasZeroDepthAndZeroAge() {
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isZero();
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OLDEST_AGE)).isZero();
    }

    @Test
    void depthCountsOnlyOpenRowsAndAgeIsTheOldestOpenRow() {
        insert("OPEN", T0.minusSeconds(120));
        insert("OPEN", T0.minusSeconds(3600));
        insert("RESOLVED", T0.minusSeconds(86_400));

        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isEqualTo(2.0);
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OLDEST_AGE)).isCloseTo(3600.0, within(1.0));
        assertThat(meters.get(OpsMetrics.EXCEPTIONS_OLDEST_AGE).gauge().getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void readingIsCachedBrieflyThenRefreshed() {
        insert("OPEN", T0.minusSeconds(10));
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isEqualTo(1.0);

        insert("OPEN", T0.minusSeconds(5));
        now.set(T0.plusSeconds(1));
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).as("within the cache window").isEqualTo(1.0);

        now.set(T0.plus(ExceptionQueueMetrics.CACHE_FOR).plusSeconds(1));
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isEqualTo(2.0);
    }

    @Test
    void anUnreadableDatabaseReportsNaNNotAStaleNumber() {
        insert("OPEN", T0);
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isEqualTo(1.0);

        jdbc.execute("DROP TABLE orchestration_exception");
        now.set(T0.plus(Duration.ofMinutes(1)));

        assertThat(gauge(OpsMetrics.EXCEPTIONS_OPEN)).isNaN();
        assertThat(gauge(OpsMetrics.EXCEPTIONS_OLDEST_AGE)).isNaN();
    }
}
