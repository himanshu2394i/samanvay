package com.samanvay.notifications.internal.repository;

import com.samanvay.notifications.internal.domain.DeliveryEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {
    boolean existsByRecipientIdAndChannelAndDedupeKey(String recipientId, String channel, String dedupeKey);

    Page<DeliveryEntity> findByRecipientId(String recipientId, Pageable pageable);

    Page<DeliveryEntity> findByStatus(String status, Pageable pageable);

    @Modifying
    @Query("update DeliveryEntity d set d.renderedBody = null where d.createdAt < :cutoff")
    int clearBodiesOlderThan(Instant cutoff);
}
