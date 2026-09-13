package com.samanvay.registry.internal.repository;

import com.samanvay.registry.internal.domain.DiscoveryGrantEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscoveryGrantRepository extends JpaRepository<DiscoveryGrantEntity, DiscoveryGrantEntity.Key> {
    boolean existsBySubjectIdAndRequesterIdAndDataCategory(UUID subjectId, String requesterId, String dataCategory);

    void deleteByConsentId(UUID consentId);
}
