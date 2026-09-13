package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSourceEntity, String> {}
