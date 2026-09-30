package com.samanvay.notifications.internal.repository;

import com.samanvay.notifications.internal.domain.DeliveryEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {
    boolean existsByRecipientIdAndChannelAndDedupeKey(String recipientId, String channel, String dedupeKey);

    Page<DeliveryEntity> findByRecipientId(String recipientId, Pageable pageable);

    Page<DeliveryEntity> findByStatus(String status, Pageable pageable);

    /**
     * FAILED deliveries still worth another attempt: fewer than {@code maxAttempts} tries so far and on
     * a channel we actually retry (EMAIL/IN_APP — the SMS stub is excluded so it never loops). Oldest
     * first, capped by the {@link Pageable}.
     */
    @Query("select d from DeliveryEntity d where d.status = 'FAILED' and d.attempts < :maxAttempts"
            + " and d.channel in :channels order by d.createdAt")
    List<DeliveryEntity> findRetryable(
            @Param("channels") Collection<String> channels, @Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Modifying
    @Query("update DeliveryEntity d set d.renderedBody = null where d.createdAt < :cutoff")
    int clearBodiesOlderThan(Instant cutoff);
}
