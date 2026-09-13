package com.samanvay.notifications.internal.service;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryRecord;
import com.samanvay.notifications.api.NotificationHistory;
import com.samanvay.notifications.api.RecipientRef;
import com.samanvay.notifications.api.Subscription;
import com.samanvay.notifications.api.SubscriptionService;
import com.samanvay.notifications.internal.domain.SubscriptionEntity;
import com.samanvay.notifications.internal.repository.DeliveryRepository;
import com.samanvay.notifications.internal.repository.SubscriptionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationServices implements SubscriptionService, NotificationHistory {

    private final SubscriptionRepository subscriptions;
    private final DeliveryRepository deliveries;

    NotificationServices(SubscriptionRepository subscriptions, DeliveryRepository deliveries) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
    }

    @Override
    @Transactional
    public Subscription subscribe(RecipientRef recipient, String eventType, Channel channel, String locale) {
        SubscriptionEntity e = new SubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setRecipientId(recipient.id());
        e.setEventType(eventType);
        e.setChannel(channel.name());
        e.setLocale(locale == null ? "en" : locale);
        e.setEnabled(true);
        subscriptions.save(e);
        return toSub(e);
    }

    @Override
    @Transactional
    public void unsubscribe(UUID subscriptionId) {
        subscriptions.findById(subscriptionId).ifPresent(s -> {
            s.setEnabled(false);
            subscriptions.save(s);
        });
    }

    @Override
    public List<Subscription> forRecipient(RecipientRef recipient) {
        return subscriptions.findByRecipientId(recipient.id()).stream().map(this::toSub).toList();
    }

    @Override
    public Page<DeliveryRecord> forRecipient(RecipientRef recipient, Pageable pageable) {
        return deliveries.findByRecipientId(recipient.id(), pageable).map(this::toDelivery);
    }

    @Override
    public Page<DeliveryRecord> failures(Pageable pageable) {
        return deliveries.findByStatus("FAILED", pageable).map(this::toDelivery);
    }

    private Subscription toSub(SubscriptionEntity e) {
        return new Subscription(e.getId(), e.getRecipientId(), e.getEventType(), Channel.valueOf(e.getChannel()), e.getLocale(), e.isEnabled());
    }

    private DeliveryRecord toDelivery(com.samanvay.notifications.internal.domain.DeliveryEntity e) {
        return new DeliveryRecord(
                e.getId(), e.getRecipientId(), e.getEventType(), Channel.valueOf(e.getChannel()), e.getStatus(), e.getRenderedBody());
    }
}
