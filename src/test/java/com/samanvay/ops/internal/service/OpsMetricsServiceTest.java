package com.samanvay.ops.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.ops.internal.service.OpsMetricsView.Latency;
import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.shared.OpsMetrics;
import com.samanvay.tracking.api.SlaOverview;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The four dashboards computed from a real {@link MeterRegistry}, and the JSON they serialize to. */
class OpsMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final JourneyService journeys = mock(JourneyService.class);
    private final AtomicReference<SlaOverview.Snapshot> sla = new AtomicReference<>(emptySla());
    private final OpsMetricsService service =
            new OpsMetricsService(meters, asOf -> sla.get(), journeys, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SlaOverview.Snapshot emptySla() {
        return new SlaOverview.Snapshot(NOW, 0, 0, 0, List.of(), List.of());
    }

    private void exchange(String source, String outcome, long millis) {
        OpsMetrics.recordConnectorExchange(meters, source, outcome, TimeUnit.MILLISECONDS.toNanos(millis));
    }

    // ---- connector health

    @Test
    void connectorHealthReportsPerSourceCountsAndP50P95FromTheTimerSnapshot() {
        for (int ms = 1; ms <= 100; ms++) {
            exchange("revenue-rest-mock", OpsMetrics.OUTCOME_SUCCESS, ms);
        }
        exchange("revenue-rest-mock", OpsMetrics.OUTCOME_FAILURE, 900);
        OpsMetrics.countConnectorCall(meters, "revenue-rest-mock", OpsMetrics.OUTCOME_UNAVAILABLE);
        exchange("education-soap-mock", OpsMetrics.OUTCOME_SUCCESS, 40);

        var connector = service.connector();

        assertThat(connector.sources()).extracting(OpsMetricsView.Source::source)
                .containsExactly("education-soap-mock", "revenue-rest-mock");
        var revenue = connector.sources().get(1);
        assertThat(revenue.calls()).isEqualTo(102);
        assertThat(revenue.success()).isEqualTo(100);
        assertThat(revenue.failure()).isEqualTo(1);
        assertThat(revenue.unavailable()).isEqualTo(1);
        assertThat(revenue.successRate()).isCloseTo(100.0 / 102, within(1e-9));
        Latency latency = revenue.latency();
        assertThat(latency.count()).isEqualTo(100);
        assertThat(latency.p50Ms()).isCloseTo(50.0, within(3.0));
        assertThat(latency.p95Ms()).isCloseTo(95.0, within(5.0));
        assertThat(latency.maxMs()).isCloseTo(100.0, within(1.0));
        assertThat(revenue.latencyByOutcome()).containsOnlyKeys("success", "failure");
        assertThat(revenue.latencyByOutcome().get("failure").p50Ms()).isCloseTo(900.0, within(45.0));
        // the single-sample source
        assertThat(connector.sources().get(0).latency().p50Ms()).isCloseTo(40.0, within(2.0));
    }

    @Test
    void aSourceThatWasOnlyEverUnavailableHasCountsButNoLatency() {
        OpsMetrics.countConnectorCall(meters, "dbt-rest-mock", OpsMetrics.OUTCOME_UNAVAILABLE);

        var source = service.connector().sources().getFirst();

        assertThat(source.calls()).isEqualTo(1);
        assertThat(source.successRate()).isZero();
        assertThat(source.latency()).isNull();
        assertThat(source.latencyByOutcome()).isEmpty();
    }

    @Test
    void noConnectorTrafficYieldsAnEmptyList() {
        assertThat(service.connector().sources()).isEmpty();
    }

    // ---- consent and access

    @Test
    void consentCountsGrantsAndDenialsByReasonMostFrequentFirst() {
        count("granted", "none", 7);
        count("denied", "NO_CONSENT", 2);
        count("denied", "CONSENT_EXPIRED", 1);
        count("denied", "FREQUENCY_EXCEEDED", 3);

        var consent = service.consent();

        assertThat(consent.granted()).isEqualTo(7);
        assertThat(consent.denied()).isEqualTo(6);
        assertThat(consent.grantRate()).isCloseTo(7.0 / 13, within(1e-9));
        assertThat(consent.denialsByReason())
                .extracting(OpsMetricsView.ReasonCount::reason, OpsMetricsView.ReasonCount::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FREQUENCY_EXCEEDED", 3L),
                        org.assertj.core.groups.Tuple.tuple("NO_CONSENT", 2L),
                        org.assertj.core.groups.Tuple.tuple("CONSENT_EXPIRED", 1L));
    }

    @Test
    void consentWithNoDecisionsHasNoRate() {
        var consent = service.consent();

        assertThat(consent.granted()).isZero();
        assertThat(consent.denied()).isZero();
        assertThat(consent.grantRate()).isNull();
        assertThat(consent.denialsByReason()).isEmpty();
    }

    private void count(String outcome, String reason, int times) {
        for (int i = 0; i < times; i++) {
            meters.counter(OpsMetrics.CONSENT_AUTHORIZE, "outcome", outcome, "reason", reason).increment();
        }
    }

    // ---- SLA

    @Test
    void slaReportsBreachesAndPercentWithinSla() {
        sla.set(new SlaOverview.Snapshot(
                NOW,
                8,
                2,
                3,
                List.of(new SlaOverview.JourneySla("SCHOLARSHIP", 8, 2, 3)),
                List.of(new SlaOverview.Case("MH-1", "SCHOLARSHIP", "SUBMITTED", NOW.minusSeconds(600), -600))));

        var view = service.sla(NOW);

        assertThat(view.open()).isEqualTo(8);
        assertThat(view.breached()).isEqualTo(2);
        assertThat(view.dueSoon()).isEqualTo(3);
        assertThat(view.withinSlaPercent()).isEqualTo(75.0);
        assertThat(view.watchlist()).singleElement().satisfies(c -> assertThat(c.secondsToDue()).isEqualTo(-600));
    }

    @Test
    void slaWithNothingOpenHasNoPercentage() {
        assertThat(service.sla(NOW).withinSlaPercent()).isNull();
    }

    // ---- exception queue

    @Test
    void exceptionQueueReadsDepthAndAgeFromGaugesAndGroupsRowsByReason() {
        Gauge.builder(OpsMetrics.EXCEPTIONS_OPEN, () -> 3.0).register(meters);
        Gauge.builder(OpsMetrics.EXCEPTIONS_OLDEST_AGE, () -> 7200.0).baseUnit("seconds").register(meters);
        when(journeys.openExceptions())
                .thenReturn(List.of(
                        row("PENDING_SOURCE", NOW.minusSeconds(60)),
                        row("FAILED", NOW.minusSeconds(7200)),
                        row("PENDING_SOURCE", NOW.minusSeconds(600))));

        var queue = service.exceptionQueue(NOW);

        assertThat(queue.open()).isEqualTo(3L);
        assertThat(queue.oldestAgeSeconds()).isEqualTo(7200.0);
        assertThat(queue.byReason())
                .extracting(OpsMetricsView.ReasonCount::reason, OpsMetricsView.ReasonCount::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PENDING_SOURCE", 2L),
                        org.assertj.core.groups.Tuple.tuple("FAILED", 1L));
        assertThat(queue.oldest()).extracting(OpsMetricsView.ExceptionRow::ageSeconds).containsExactly(7200L, 600L, 60L);
    }

    @Test
    void unreadableGaugesAreNullNotNaNOrZero() {
        Gauge.builder(OpsMetrics.EXCEPTIONS_OPEN, () -> Double.NaN).register(meters);
        when(journeys.openExceptions()).thenReturn(List.of());

        var queue = service.exceptionQueue(NOW);

        assertThat(queue.open()).isNull();
        assertThat(queue.oldestAgeSeconds()).as("gauge not registered").isNull();
    }

    private static JourneyExceptionView row(String reason, Instant createdAt) {
        return new JourneyExceptionView(UUID.randomUUID(), UUID.randomUUID(), "INCOME", reason, createdAt);
    }

    // ---- JSON shape

    @Test
    void jsonHasTheFourDashboardsAndNoNaN() {
        exchange("revenue-rest-mock", OpsMetrics.OUTCOME_SUCCESS, 25);
        count("granted", "none", 1);
        count("denied", "NO_CONSENT", 1);
        when(journeys.openExceptions()).thenReturn(List.of(row("FAILED", NOW.minusSeconds(30))));

        JsonNode json = JsonMapper.builder().build().valueToTree(service.metrics());

        assertThat(json.propertyNames()).contains("generatedAt", "connector", "sla", "consent", "exceptionQueue");
        assertThat(json.get("generatedAt").asString()).isEqualTo("2026-09-29T10:00:00Z");
        JsonNode source = json.get("connector").get("sources").get(0);
        assertThat(source.get("source").asString()).isEqualTo("revenue-rest-mock");
        assertThat(source.get("latency").propertyNames()).contains("count", "p50Ms", "p95Ms", "meanMs", "maxMs");
        assertThat(source.get("latencyByOutcome").has("success")).isTrue();
        assertThat(json.get("connector").get("latencyWindowMinutes").asInt()).isEqualTo(10);
        assertThat(json.get("sla").propertyNames()).contains("open", "breached", "dueSoon", "withinSlaPercent", "byJourney", "watchlist");
        assertThat(json.get("consent").get("denialsByReason").get(0).get("reason").asString()).isEqualTo("NO_CONSENT");
        assertThat(json.get("exceptionQueue").get("oldest").get(0).get("stepCode").asString()).isEqualTo("INCOME");
        assertThat(json.toString()).doesNotContain("NaN");
    }
}
