package com.samanvay.orchestration.internal.repository;

import com.samanvay.orchestration.internal.domain.StepStateEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepStateRepository extends JpaRepository<StepStateEntity, UUID> {
    List<StepStateEntity> findByInstanceId(UUID instanceId);
}
