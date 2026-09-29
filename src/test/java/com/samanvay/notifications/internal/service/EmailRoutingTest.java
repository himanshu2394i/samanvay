package com.samanvay.notifications.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.internal.channel.EmailChannel;
import com.samanvay.notifications.internal.channel.TemplateRenderer;
import com.samanvay.notifications.internal.domain.DeliveryEntity;
import com.samanvay.notifications.internal.domain.SubscriptionEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import com.samanvay.notifications.internal.repository.SubscriptionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An EMAIL / SMS subscription reaches the matching channel with the subscription's address. */
class EmailRoutingTest {

    private static SubscriptionEntity sub(String channel, String address) {
        SubscriptionEntity s = new SubscriptionEntity();
        s.setRecipientId("citizen-1");
        s.setEventType("JourneyStarted");
        s.setChannel(channel);
        s.setLocale("en");
        s.setEnabled(true);
        s.setAddress(address);
        return s;
    }

    private static org.springframework.beans.factory.ObjectProvider<org.springframework.mail.javamail.JavaMailSender> providerOf(
            org.springframework.mail.javamail.JavaMailSender sender) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public org.springframework.mail.javamail.JavaMailSender getObject() {
                return sender;
            }

            @Override
            public org.springframework.mail.javamail.JavaMailSender getObject(Object... args) {
                return sender;
            }

            @Override
            public org.springframework.mail.javamail.JavaMailSender getIfAvailable() {
                return sender;
            }

            @Override
            public org.springframework.mail.javamail.JavaMailSender getIfUnique() {
                return sender;
            }
        };
    }

    private static NotificationChannel recording(Channel channel, List<Object> seen, DeliveryOutcome outcome) {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.channel()).thenReturn(channel);
        when(c.send(any())).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return outcome;
        });
        return c;
    }

    private static NotificationDispatcher dispatcher(
            List<SubscriptionEntity> subs, List<DeliveryEntity> saved, List<NotificationChannel> channels, List<Object> events) {
        SubscriptionRepository repo = mock(SubscriptionRepository.class);
        when(repo.findByRecipientIdAndEventTypeAndEnabledTrue(any(), any())).thenReturn(subs);
        when(repo.findByRecipientId(any())).thenReturn(subs);
        DeliveryRepository deliveries = mock(DeliveryRepository.class);
        when(deliveries.existsByRecipientIdAndChannelAndDedupeKey(any(), any(), any())).thenReturn(false);
        when(deliveries.save(any(DeliveryEntity.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return new NotificationDispatcher(
                repo, deliveries, new TemplateRenderer(), channels, events::add, new PendingCandidateAlerts());
    }

    @Test
    void emailSubscriptionRoutesToEmailChannelWithSubscriptionAddress() {
        List<Object> seen = new ArrayList<>();
        NotificationDispatcher d = dispatcher(
                List.of(sub("EMAIL", "asha@example.test")),
                new ArrayList<>(),
                List.of(recording(Channel.EMAIL, seen, new DeliveryOutcome(true, null))),
                new ArrayList<>());

        d.dispatch("JourneyStarted", "citizen-1", "src-1", Map.of("journeyCode", "scholarship", "summary", "scholarship"));

        assertThat(seen).hasSize(1);
        var msg = (com.samanvay.notifications.api.RenderedMessage) seen.get(0);
        assertThat(msg.address()).isEqualTo("asha@example.test");
        assertThat(msg.subject()).contains("JourneyStarted");
        assertThat(msg.recipientId()).isEqualTo("citizen-1");
    }

    @Test
    void emailFailureIsRecordedAsFailedDeliveryAndDoesNotThrow() {
        List<DeliveryEntity> saved = new ArrayList<>();
        List<Object> events = new ArrayList<>();
        NotificationDispatcher d = dispatcher(
                List.of(sub("EMAIL", "asha@example.test")),
                saved,
                List.of(recording(Channel.EMAIL, new ArrayList<>(), new DeliveryOutcome(false, "smtp down"))),
                events);

        d.dispatch("JourneyStarted", "citizen-1", "src-1", Map.of("journeyCode", "scholarship", "summary", "scholarship"));

        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo("FAILED");
        assertThat(events).hasSize(1);
    }

    @Test
    void smsSubscriptionWithStubOutcomeIsFailedNotSentAndDoesNotThrow() {
        List<DeliveryEntity> saved = new ArrayList<>();
        NotificationDispatcher d = dispatcher(
                List.of(sub("SMS", "+911234567890")),
                saved,
                List.of(recording(Channel.SMS, new ArrayList<>(), new DeliveryOutcome(false, "stub"))),
                new ArrayList<>());

        d.dispatch("JourneyStarted", "citizen-1", "src-1", Map.of("journeyCode", "scholarship", "summary", "scholarship"));

        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void emailSubscriptionEndToEndThroughRealChannelIntoGreenMail() throws Exception {
        GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        greenMail.start();
        try {
            var sender = new org.springframework.mail.javamail.JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(greenMail.getSmtp().getPort());
            NotificationChannel email = new EmailChannel(providerOf(sender), "no-reply@samanvay.test");
            List<DeliveryEntity> saved = new ArrayList<>();
            NotificationDispatcher d = dispatcher(
                    List.of(sub("EMAIL", "asha@example.test")), saved, List.of(email), new ArrayList<>());

            d.dispatch("JourneyStarted", "citizen-1", "src-1", Map.of("journeyCode", "scholarship", "summary", "scholarship"));

            assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo("SENT");
            assertThat(greenMail.waitForIncomingEmail(2000, 1)).isTrue();
            var received = greenMail.getReceivedMessages()[0];
            assertThat(received.getAllRecipients()[0].toString()).isEqualTo("asha@example.test");
            assertThat(received.getSubject()).contains("JourneyStarted");
        } finally {
            greenMail.stop();
        }
    }
}
