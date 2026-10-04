package com.samanvay.ops.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One journey as the staff journey page sees it: is each required category connected and working, how are its
 * applications going, and the middle-layer log. Read-only; no secrets, no citizen values, no document payloads.
 */
public record JourneyStatusView(
        String code,
        String name,
        String requester,
        String status,
        String portalUrl,
        List<Category> categories,
        Counts counts,
        List<Recent> recent,
        List<LogRow> log) {

    /**
     * @param connectorRef the published connector serving this category, null when none
     * @param connectorStatus its status, {@code NONE} when there is no published connector
     * @param sourceHealth GREEN, AMBER, RED or UNKNOWN (the last probe of its data source)
     * @param working a published connector exists and its source is GREEN or AMBER (an SFTP/JDBC source, always UNKNOWN, counts when its last trial succeeded)
     */
    public record Category(
            String category,
            String department,
            String connectorRef,
            String connectorStatus,
            String dataSourceCode,
            String sourceHealth,
            Trial lastTrial,
            boolean working) {}

    public record Trial(Instant at, String outcome) {}

    /** @param last7Days applications of any state started in the last seven days */
    public record Counts(long running, long completed, long failed, long last7Days) {}

    public record Recent(UUID instanceId, String referenceNo, String state, Instant startedAt) {}

    /**
     * One step of one recent application. {@code latencyMs} is how long the step took (null while it is unfinished);
     * {@code error} is why it did not complete (null when it did).
     */
    public record LogRow(
            Instant at,
            String referenceNo,
            String category,
            String department,
            String connector,
            String outcome,
            Long latencyMs,
            String error) {}
}
