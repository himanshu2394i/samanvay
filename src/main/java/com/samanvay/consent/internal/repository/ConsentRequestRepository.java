package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.ConsentRequestEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRequestRepository extends JpaRepository<ConsentRequestEntity, UUID> {}
