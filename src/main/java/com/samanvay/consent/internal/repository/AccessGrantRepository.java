package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.AccessGrantEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccessGrantRepository extends JpaRepository<AccessGrantEntity, UUID> {
    Optional<AccessGrantEntity> findByNonce(byte[] nonce);

    long countByConsentIdAndIssuedAtAfter(UUID consentId, Instant after);

    @Modifying
    @Query("UPDATE AccessGrantEntity g SET g.usedAt = :usedAt WHERE g.nonce = :nonce AND g.usedAt IS NULL")
    int markUsedIfUnused(byte[] nonce, Instant usedAt);
}
