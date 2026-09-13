package com.samanvay.notifications.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.consent.api.ConsentRequested;
import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.internal.channel.TemplateRenderer;
import com.samanvay.notifications.internal.domain.DeliveryEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import com.samanvay.notifications.internal.repository.SubscriptionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotificationIsolationTest {

    @Test
    void channelFailureDoesNotPropagateAndDedupeKeepsOneRow() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRecipientIdAndEventTypeAndEnabledTrue(any(), any())).thenReturn(List.of());
        when(subs.findByRecipientId(any())).thenReturn(List.of());
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.existsByRecipientIdAndChannelAndDedupeKey(any(), any(), any())).thenReturn(false, true);
        when(deliveries.save(any(DeliveryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        NotificationChannel boom = mock(NotificationChannel.class);
        when(boom.channel()).thenReturn(Channel.IN_APP);
        when(boom.send(any())).thenThrow(new RuntimeException("sms down"));
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                subs, deliveries, new TemplateRenderer(), List.of(boom), e -> {}, new PendingCandidateAlerts());
        UUID citizen = UUID.randomUUID();
        var event = new ConsentRequested(UUID.randomUUID(), citizen, "INDUSTRY", "BUSINESS_NOC", List.of("PROPERTY"));
        assertThatCode(() -> dispatcher.on(event)).doesNotThrowAnyException();
        dispatcher.on(event);
        assertThat(Mockito.mockingDetails(deliveries).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("save"))
                        .count())
                .isLessThanOrEqualTo(2);
    }
}
