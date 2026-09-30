package com.samanvay.ops.internal.service;

import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.ops.internal.service.OpsMetricsView.Connector;
import com.samanvay.ops.internal.service.OpsMetricsView.Consent;
import com.samanvay.ops.internal.service.OpsMetricsView.ExceptionQueue;
import com.samanvay.ops.internal.service.OpsMetricsView.ExceptionRow;
import com.samanvay.ops.internal.service.OpsMetricsView.Latency;
import com.samanvay.ops.internal.service.OpsMetricsView.Notifications;
import com.samanvay.ops.internal.service.OpsMetricsView.ReasonCount;
import com.samanvay.ops.internal.service.OpsMetricsView.Sla;
import com.samanvay.ops.internal.service.OpsMetricsView.Source;
import com.samanvay.shared.OpsMetrics;
import com.samanvay.tracking.api.SlaOverview;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Computes the four dashboards from the in-process {@link MeterRegistry} (connector, consent, queue
 * depth and age) and from read-only queries behind the tracking and orchestration APIs (SLA rows,
 * exception rows). Read-only; nothing here is exposed except through the staff-secured controller.
 */
@Service
public class OpsMetricsService {

    /** How many of the oldest open exceptions the queue dashboard lists. */
    static final int OLDEST_EXCEPTIONS = 10;

    private final MeterRegistry meters;
    private final SlaOverview slaOverview;
    private final JourneyService journeys;
    private final Clock clock;

    OpsMetricsService(MeterRegistry meters, SlaOverview slaOverview, JourneyService journeys, Clock clock) {
        this.meters = meters;
        this.slaOverview = slaOverview;
        this.journeys = journeys;
        this.clock = clock;
    }

    public OpsMetricsView metrics() {
        Instant now = clock.instant();
        return new OpsMetricsView(now, connector(), sla(now), consent(), exceptionQueue(now), notifications());
    }

    // ---- connector health

    Connector connector() {
        Map<String, Map<String, Long>> calls = new TreeMap<>();
        for (Counter c : meters.find(OpsMetrics.CONNECTOR_CALLS).counters()) {
            String source = c.getId().getTag(OpsMetrics.TAG_SOURCE);
            String outcome = c.getId().getTag(OpsMetrics.TAG_OUTCOME);
            if (source != null && outcome != null) {
                calls.computeIfAbsent(source, k -> new TreeMap<>()).merge(outcome, (long) c.count(), Long::sum);
            }
        }
        Map<String, Map<String, Latency>> latencies = new TreeMap<>();
        for (Timer t : meters.find(OpsMetrics.CONNECTOR_EXCHANGE).timers()) {
            String source = t.getId().getTag(OpsMetrics.TAG_SOURCE);
            String outcome = t.getId().getTag(OpsMetrics.TAG_OUTCOME);
            if (source != null && outcome != null) {
                latencies.computeIfAbsent(source, k -> new TreeMap<>()).put(outcome, latency(t));
                calls.computeIfAbsent(source, k -> new TreeMap<>());
            }
        }
        List<Source> sources = calls.entrySet().stream()
                .map(e -> source(e.getKey(), e.getValue(), latencies.getOrDefault(e.getKey(), Map.of())))
                .toList();
        return new Connector((int) OpsMetrics.LATENCY_WINDOW.toMinutes(), sources);
    }

    private static Source source(String source, Map<String, Long> byOutcome, Map<String, Latency> latencies) {
        long success = byOutcome.getOrDefault(OpsMetrics.OUTCOME_SUCCESS, 0L);
        long failure = byOutcome.getOrDefault(OpsMetrics.OUTCOME_FAILURE, 0L);
        long unavailable = byOutcome.getOrDefault(OpsMetrics.OUTCOME_UNAVAILABLE, 0L);
        long total = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        return new Source(
                source,
                total,
                success,
                failure,
                unavailable,
                total == 0 ? null : (double) success / total,
                latencies.get(OpsMetrics.OUTCOME_SUCCESS),
                new LinkedHashMap<>(latencies));
    }

    /** p50/p95 come from the timer's percentile snapshot; a percentile that is not (yet) known is null. */
    static Latency latency(Timer timer) {
        HistogramSnapshot snap = timer.takeSnapshot();
        return new Latency(
                snap.count(),
                percentileMs(snap, 0.5),
                percentileMs(snap, 0.95),
                snap.count() == 0 ? null : finite(snap.mean(TimeUnit.MILLISECONDS)),
                snap.count() == 0 ? null : finite(snap.max(TimeUnit.MILLISECONDS)));
    }

    private static Double percentileMs(HistogramSnapshot snap, double percentile) {
        for (ValueAtPercentile v : snap.percentileValues()) {
            if (Math.abs(v.percentile() - percentile) < 1e-9) {
                return snap.count() == 0 ? null : finite(v.value(TimeUnit.MILLISECONDS));
            }
        }
        return null;
    }

    // ---- SLA

    Sla sla(Instant now) {
        SlaOverview.Snapshot s = slaOverview.snapshot(now);
        Double within = s.open() == 0 ? null : 100.0 * (s.open() - s.breached()) / s.open();
        return new Sla(s.open(), s.breached(), s.dueSoon(), within, s.byJourney(), s.watchlist());
    }

    // ---- consent and access

    Consent consent() {
        long granted = 0;
        long denied = 0;
        Map<String, Long> reasons = new TreeMap<>();
        for (Counter c : meters.find(OpsMetrics.CONSENT_AUTHORIZE).counters()) {
            String outcome = c.getId().getTag(OpsMetrics.TAG_OUTCOME);
            long n = (long) c.count();
            if (OpsMetrics.OUTCOME_GRANTED.equals(outcome)) {
                granted += n;
            } else if (OpsMetrics.OUTCOME_DENIED.equals(outcome)) {
                denied += n;
                reasons.merge(String.valueOf(c.getId().getTag(OpsMetrics.TAG_REASON)), n, Long::sum);
            }
        }
        List<ReasonCount> byReason = reasons.entrySet().stream()
                .map(e -> new ReasonCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ReasonCount::count).reversed().thenComparing(ReasonCount::reason))
                .toList();
        long total = granted + denied;
        return new Consent(granted, denied, total == 0 ? null : (double) granted / total, byReason);
    }

    // ---- notification delivery health

    Notifications notifications() {
        long sent = 0;
        long failed = 0;
        for (Counter c : meters.find(OpsMetrics.NOTIFICATION_DELIVERY).counters()) {
            String outcome = c.getId().getTag(OpsMetrics.TAG_OUTCOME);
            long n = (long) c.count();
            if (OpsMetrics.OUTCOME_SENT.equals(outcome)) {
                sent += n;
            } else if (OpsMetrics.OUTCOME_FAILED.equals(outcome)) {
                failed += n;
            }
        }
        long retriedSent = 0;
        long retriedFailed = 0;
        for (Counter c : meters.find(OpsMetrics.NOTIFICATION_RETRY).counters()) {
            String outcome = c.getId().getTag(OpsMetrics.TAG_OUTCOME);
            long n = (long) c.count();
            if (OpsMetrics.OUTCOME_SENT.equals(outcome)) {
                retriedSent += n;
            } else if (OpsMetrics.OUTCOME_FAILED.equals(outcome)) {
                retriedFailed += n;
            }
        }
        long total = sent + failed;
        return new Notifications(sent, failed, retriedSent, retriedFailed, total == 0 ? null : (double) sent / total);
    }

    // ---- exception queue

    ExceptionQueue exceptionQueue(Instant now) {
        Double depth = gauge(OpsMetrics.EXCEPTIONS_OPEN);
        Double oldestAge = gauge(OpsMetrics.EXCEPTIONS_OLDEST_AGE);
        List<JourneyExceptionView> open = journeys.openExceptions();
        Map<String, Long> reasons = new TreeMap<>();
        open.forEach(x -> reasons.merge(x.reason() == null ? "UNSPECIFIED" : x.reason(), 1L, Long::sum));
        List<ReasonCount> byReason = reasons.entrySet().stream()
                .map(e -> new ReasonCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ReasonCount::count).reversed().thenComparing(ReasonCount::reason))
                .toList();
        List<ExceptionRow> oldest = open.stream()
                .sorted(Comparator.comparing(JourneyExceptionView::createdAt))
                .limit(OLDEST_EXCEPTIONS)
                .map(x -> new ExceptionRow(
                        x.id(),
                        x.instanceId(),
                        x.stepCode(),
                        x.reason(),
                        x.createdAt(),
                        Math.max(0, Duration.between(x.createdAt(), now).toSeconds())))
                .toList();
        return new ExceptionQueue(depth == null ? null : Math.round(depth), oldestAge, byReason, oldest);
    }

    private Double gauge(String name) {
        Gauge g = meters.find(name).gauge();
        return g == null ? null : finite(g.value());
    }

    private static Double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
