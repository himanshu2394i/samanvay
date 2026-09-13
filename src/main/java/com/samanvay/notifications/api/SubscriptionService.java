package com.samanvay.notifications.api;

import java.util.List;
import java.util.UUID;

public interface SubscriptionService {
    Subscription subscribe(RecipientRef recipient, String eventType, Channel channel, String locale);

    void unsubscribe(UUID subscriptionId);

    List<Subscription> forRecipient(RecipientRef recipient);
}
