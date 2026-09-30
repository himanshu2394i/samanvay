package com.samanvay.ops.internal.service;

import com.samanvay.tracking.api.SlaOverview;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON shape of {@code GET /api/ops/metrics}: the four ops dashboards (HLD section 10). Numbers that
 * cannot be computed (no calls yet, gauge unreadable) are {@code null}, never NaN or a made-up zero.
 */
public record OpsMetricsView(
        Instant generatedAt,
        Connector connector,
        Sla sla,
        Consent consent,
        ExceptionQueue exceptionQueue,
        Notifications notifications) {

    /**
     * Notification delivery health since this node started: first-attempt outcomes and retry outcomes,
     * by count. {@code sentRate} is the first-attempt success rate, {@code null} until one is attempted.
     */
    public record Notifications(long sent, long failed, long retriedSent, long retriedFailed, Double sentRate) {}

    /**
     * Connector health per data source. Call counts are cumulative since this node started;
     * latency percentiles cover the last {@code latencyWindowMinutes} on this node only.
     */
    public record Connector(int latencyWindowMinutes, List<Source> sources) {}

    /**
     * @param latency latency of successful exchanges (the headline number); {@code null} until one is recorded
     * @param latencyByOutcome latency per outcome (success, failure, unavailable) that has been recorded
     */
    public record Source(
            String source,
            long calls,
            long success,
            long failure,
            long unavailable,
            Double successRate,
            Latency latency,
            Map<String, Latency> latencyByOutcome) {}

    public record Latency(long count, Double p50Ms, Double p95Ms, Double meanMs, Double maxMs) {}

    /** Open applications: SLA due time against {@code generatedAt}. */
    public record Sla(
            long open,
            long breached,
            long dueSoon,
            Double withinSlaPercent,
            List<SlaOverview.JourneySla> byJourney,
            List<SlaOverview.Case> watchlist) {}

    /** Access authorize decisions since this node started. */
    public record Consent(long granted, long denied, Double grantRate, List<ReasonCount> denialsByReason) {}

    public record ReasonCount(String reason, long count) {}

    /**
     * @param open queue depth from the gauge; {@code null} if it is not readable
     * @param oldestAgeSeconds age of the oldest open exception; {@code null} if not readable
     * @param byReason open exceptions grouped by reason
     * @param oldest the oldest open exceptions
     */
    public record ExceptionQueue(
            Long open, Double oldestAgeSeconds, List<ReasonCount> byReason, List<ExceptionRow> oldest) {}

    public record ExceptionRow(UUID id, UUID instanceId, String stepCode, String reason, Instant createdAt, long ageSeconds) {}
}
