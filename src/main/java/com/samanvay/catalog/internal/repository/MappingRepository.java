package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.MappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRepository extends JpaRepository<MappingEntity, String> {}
