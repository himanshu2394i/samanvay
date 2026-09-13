package com.samanvay.tracking.internal.repository;

import com.samanvay.tracking.internal.domain.ApplicationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {
    Optional<ApplicationEntity> findByReferenceNo(String referenceNo);

    Page<ApplicationEntity> findByCitizenId(UUID citizenId, Pageable pageable);
}
