package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.ConsentEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentEventRepository extends JpaRepository<ConsentEventEntity, UUID> {}
