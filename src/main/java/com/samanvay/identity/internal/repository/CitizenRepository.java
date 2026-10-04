package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.CitizenEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CitizenRepository extends JpaRepository<CitizenEntity, UUID> {

    Optional<CitizenEntity> findByAuthSubject(String authSubject);

    boolean existsByIdAndAuthSubject(UUID id, String authSubject);

    /** The citizen row, locked for the rest of the transaction (SELECT ... FOR UPDATE), so a merge and a link of the same citizen take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CitizenEntity c where c.id = :id")
    Optional<CitizenEntity> lockById(UUID id);
}
