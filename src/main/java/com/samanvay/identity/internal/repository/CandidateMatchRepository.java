package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateMatchRepository extends JpaRepository<CandidateMatchEntity, UUID> {
    Page<CandidateMatchEntity> findByStatus(String status, Pageable pageable);

    Optional<CandidateMatchEntity> findByCitizenIdAndDepartmentCode(UUID citizenId, String departmentCode);
}
