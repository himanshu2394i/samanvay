package com.samanvay.notifications.internal.service;

import com.samanvay.notifications.internal.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ScheduledJobs {

    private final PendingCandidateAlerts pending;
    private final NotificationDispatcher dispatcher;
    private final DeliveryRepository deliveries;

    ScheduledJobs(PendingCandidateAlerts pending, NotificationDispatcher dispatcher, DeliveryRepository deliveries) {
        this.pending = pending;
        this.dispatcher = dispatcher;
        this.deliveries = deliveries;
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
