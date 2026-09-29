package com.samanvay.notifications.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.internal.channel.TemplateRenderer;
import com.samanvay.notifications.internal.domain.DeliveryEntity;
import com.samanvay.notifications.internal.domain.SubscriptionEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import com.samanvay.notifications.internal.repository.SubscriptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Delivery retries: a FAILED email row is re-attempted and settles SENT or stays FAILED, and the job only sweeps retryable channels. */
class NotificationRetryTest {

    private static SubscriptionEntity emailSub(String address) {
        SubscriptionEntity s = new SubscriptionEntity();
        s.setRecipientId("citizen-1");
        s.setEventType("ApplicationReferenceIssued");
        s.setChannel("EMAIL");
        s.setLocale("en");
        s.setEnabled(true);
        s.setAddress(address);
        return s;
    }

    private static DeliveryEntity failedEmail() {
        DeliveryEntity row = new DeliveryEntity();
        row.setId(UUID.randomUUID());
        row.setRecipientId("citizen-1");
        row.setEventType("ApplicationReferenceIssued");
        row.setChannel("EMAIL");
        row.setRenderedBody("Your reference is SCH-1");
        row.setStatus("FAILED");
        row.setAttempts(1);
        row.setLastError("smtp down");
        row.setCreatedAt(Instant.now());
        return row;
    }

    private static NotificationChannel channel(Channel which, DeliveryOutcome outcome) {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.channel()).thenReturn(which);
        when(c.send(any())).thenReturn(outcome);
        return c;
    }

    private static NotificationDispatcher dispatcher(DeliveryRepository deliveries, NotificationChannel channel) {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRecipientIdAndEventTypeAndEnabledTrue(any(), any())).thenReturn(List.of(emailSub("asha@example.test")));
        return new NotificationDispatcher(
                subs,
                deliveries,
                mock(TemplateRenderer.class),
                List.of(channel),
                mock(ApplicationEventPublisher.class),
                mock(PendingCandidateAlerts.class));
    }

    @Test
    void retrySendsAndFlipsRowToSentIncrementingAttempts() {
        DeliveryEntity row = failedEmail();
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.findById(row.getId())).thenReturn(Optional.of(row));
        when(deliveries.save(any(DeliveryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean sent = dispatcher(deliveries, channel(Channel.EMAIL, new DeliveryOutcome(true, null))).retry(row.getId());

        assertThat(sent).isTrue();
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getSentAt()).isNotNull();
    }

    @Test
    void retryThatFailsAgainStaysFailedAndRecordsTheError() {
        DeliveryEntity row = failedEmail();
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.findById(row.getId())).thenReturn(Optional.of(row));
        when(deliveries.save(any(DeliveryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean sent = dispatcher(deliveries, channel(Channel.EMAIL, new DeliveryOutcome(false, "still down"))).retry(row.getId());

        assertThat(sent).isFalse();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getAttempts()).isEqualTo(2); // attempt was spent
        assertThat(row.getLastError()).isEqualTo("still down");
    }

    @Test
    void aRowThatIsNoLongerFailedIsNotResent() {
        DeliveryEntity row = failedEmail();
        row.setStatus("SENT");
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.findById(row.getId())).thenReturn(Optional.of(row));

        assertThat(dispatcher(deliveries, channel(Channel.EMAIL, new DeliveryOutcome(true, null))).retry(row.getId()))
                .isFalse();
    }

    @Test
    void theJobSweepsOnlyRetryableChannelsAndRetriesEachRow() {
        DeliveryEntity a = failedEmail();
        DeliveryEntity b = failedEmail();
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.findRetryable(eq(ScheduledJobs.RETRYABLE_CHANNELS), anyInt(), any()))
                .thenReturn(List.of(a, b));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);

        new ScheduledJobs(mock(PendingCandidateAlerts.class), dispatcher, deliveries, 5).retryFailedDeliveries();

        verify(dispatcher).retry(a.getId());
        verify(dispatcher).retry(b.getId());
        verify(dispatcher, times(2)).retry(any());
        assertThat(ScheduledJobs.RETRYABLE_CHANNELS).contains("EMAIL").doesNotContain("SMS");
    }
}
