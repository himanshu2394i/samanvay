package com.samanvay.notifications.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.samanvay.identity.api.CandidateRaised;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewerDigestBatchingTest {

    @Test
    void tenCandidatesBecomeOneDigest() {
        PendingCandidateAlerts pending = new PendingCandidateAlerts();
        for (int i = 0; i < 10; i++) {
            pending.add(new CandidateRaised(UUID.randomUUID(), UUID.randomUUID(), "FIRE", 0.7));
        }
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        ScheduledJobs jobs = new ScheduledJobs(pending, dispatcher, mock(DeliveryRepository.class));
        jobs.sendReviewerDigest();
        verify(dispatcher, times(1)).dispatchDirect(eq("CandidateDigest"), eq("identity-reviewers"), any(), any());
    }
}
