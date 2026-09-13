package com.samanvay.tracking.internal.repository;

import com.samanvay.tracking.internal.domain.StepEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRepository extends JpaRepository<StepEntity, UUID> {
    List<StepEntity> findByApplicationId(UUID applicationId);

    Optional<StepEntity> findByApplicationIdAndStepCode(UUID applicationId, String stepCode);
}
