package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CandidateMatchRepository extends JpaRepository<CandidateMatchEntity, UUID> {
    Page<CandidateMatchEntity> findByStatus(String status, Pageable pageable);

    Optional<CandidateMatchEntity> findByCitizenIdAndDepartmentCode(UUID citizenId, String departmentCode);

    /** The candidate, locked until the transaction ends, so two reviewers deciding it take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CandidateMatchEntity c where c.id = :id")
    Optional<CandidateMatchEntity> lockById(UUID id);
}
