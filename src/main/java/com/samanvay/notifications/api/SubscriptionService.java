package com.samanvay.notifications.api;

import java.util.List;
import java.util.UUID;

public interface SubscriptionService {
    Subscription subscribe(RecipientRef recipient, String eventType, Channel channel, String locale);

    /**
     * Subscribe with a contact address, required for EMAIL (the e-mail address) and SMS (the phone number).
     */
    Subscription subscribe(RecipientRef recipient, String eventType, Channel channel, String locale, String address);

    void unsubscribe(UUID subscriptionId);

    List<Subscription> forRecipient(RecipientRef recipient);
}
