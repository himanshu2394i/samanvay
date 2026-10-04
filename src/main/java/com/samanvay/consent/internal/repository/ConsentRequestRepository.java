package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.ConsentRequestEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ConsentRequestRepository extends JpaRepository<ConsentRequestEntity, UUID> {

    boolean existsBySubjectCitizenId(UUID citizenId);

    /**
     * PENDING to GRANTED in one conditional UPDATE: a second grant of the same request waits for the first to commit, then matches
     * no row (0), whatever the persistence context holds. The row lock is what makes a double submit make one consent.
     */
    @Transactional
    @Modifying
    @Query("update ConsentRequestEntity r set r.status = 'GRANTED', r.respondedAt = :now where r.id = :id and r.status = 'PENDING'")
    int markGranted(UUID id, Instant now);
}
