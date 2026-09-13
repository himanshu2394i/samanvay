package com.samanvay.orchestration.internal.repository;

import com.samanvay.orchestration.internal.domain.InstanceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<InstanceEntity, UUID> {}
