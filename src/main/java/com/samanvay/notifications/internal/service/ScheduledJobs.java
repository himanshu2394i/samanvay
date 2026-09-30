package com.samanvay.notifications.internal.service;

import com.samanvay.notifications.internal.domain.DeliveryEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ScheduledJobs {

    /** Channels worth retrying. The SMS channel is a permanent stub, so retrying it would only loop. */
    static final Set<String> RETRYABLE_CHANNELS = Set.of("EMAIL", "IN_APP");
    private static final int RETRY_BATCH = 100;

    private final PendingCandidateAlerts pending;
    private final NotificationDispatcher dispatcher;
    private final DeliveryRepository deliveries;
    private final int maxAttempts;

    ScheduledJobs(
            PendingCandidateAlerts pending,
            NotificationDispatcher dispatcher,
            DeliveryRepository deliveries,
            @Value("${samanvay.notifications.retry.max-attempts:5}") int maxAttempts) {
        this.pending = pending;
        this.dispatcher = dispatcher;
        this.deliveries = deliveries;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Re-attempts FAILED deliveries on retryable channels until they send or hit {@code maxAttempts}
     * (attempts start at 1 on first send). Each row is retried in its own transaction inside the
     * dispatcher, so one persistent failure never blocks the rest of the batch.
     */
    @Scheduled(cron = "0 */5 * * * *")
    void retryFailedDeliveries() {
        for (DeliveryEntity row :
                deliveries.findRetryable(RETRYABLE_CHANNELS, maxAttempts, PageRequest.of(0, RETRY_BATCH))) {
            dispatcher.retry(row.getId());
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    void sendReviewerDigest() {
        var items = pending.drain();
        if (items.isEmpty()) {
            return;
        }
        dispatcher.dispatchDirect(
                "CandidateDigest",
                "identity-reviewers",
                Instant.now().toString(),
                Map.of("count", String.valueOf(items.size()), "summary", String.valueOf(items.size())));
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    void purgeOldBodies() {
        deliveries.clearBodiesOlderThan(Instant.now().minus(Duration.ofDays(90)));
    }
}
