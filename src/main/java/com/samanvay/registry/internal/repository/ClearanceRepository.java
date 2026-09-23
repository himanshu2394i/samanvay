package com.samanvay.registry.internal.repository;

import com.samanvay.registry.internal.domain.ClearanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClearanceRepository extends JpaRepository<ClearanceEntity, ClearanceEntity.Key> {
    boolean existsByRequesterIdAndSensitivity(String requesterId, String sensitivity);
}
