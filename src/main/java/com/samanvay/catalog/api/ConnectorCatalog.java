package com.samanvay.catalog.api;

import com.samanvay.shared.DataCategory;
import java.util.List;
import java.util.Optional;

public interface ConnectorCatalog {
    Optional<ConnectorDefinition> resolve(String departmentCode, DataCategory category, Capability capability);

    ConnectorDefinition byRef(String connectorRef);

    List<ConnectorDefinition> published();

    DataSourceDefinition dataSourceFor(ConnectorDefinition connector);

    ConnectorDefinition createDraft(ConnectorDraft draft);

    ConnectorDefinition publish(String connectorRef, ConnectorTestReport testReport);

    ConnectorDefinition newVersion(String connectorId, ConnectorDraft draft);

    MappingDefinition mapping(String mappingRef);
}
