package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.SchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemaRepository extends JpaRepository<SchemaEntity, String> {}
