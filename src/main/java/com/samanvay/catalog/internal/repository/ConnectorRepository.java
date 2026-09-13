package com.samanvay.catalog.internal.repository;

import com.samanvay.catalog.internal.domain.ConnectorEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorRepository extends JpaRepository<ConnectorEntity, String> {
    List<ConnectorEntity> findByStatus(String status);

    List<ConnectorEntity> findByConnectorId(String connectorId);

    List<ConnectorEntity> findByDataSourceCodeAndDataCategoryAndStatus(
            String dataSourceCode, String dataCategory, String status);
}
