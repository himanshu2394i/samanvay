package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.PurposeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurposeRepository extends JpaRepository<PurposeEntity, String> {}
