package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.JourneyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JourneyRepository extends JpaRepository<JourneyEntity, String> {}
