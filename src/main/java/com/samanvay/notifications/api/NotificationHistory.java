package com.samanvay.notifications.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationHistory {
    Page<DeliveryRecord> forRecipient(RecipientRef recipient, Pageable pageable);

    Page<DeliveryRecord> failures(Pageable pageable);
}
