package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, String> {}
