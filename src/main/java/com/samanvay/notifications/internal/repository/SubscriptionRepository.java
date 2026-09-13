package com.samanvay.notifications.internal.repository;

import com.samanvay.notifications.internal.domain.SubscriptionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {
    List<SubscriptionEntity> findByRecipientIdAndEventTypeAndEnabledTrue(String recipientId, String eventType);

    List<SubscriptionEntity> findByRecipientId(String recipientId);
}
