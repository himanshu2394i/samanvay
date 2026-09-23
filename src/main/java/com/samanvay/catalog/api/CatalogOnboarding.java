package com.samanvay.catalog.api;

public interface CatalogOnboarding {
    Department registerDepartment(DepartmentDraft draft);

    DataSourceDefinition registerDataSource(DataSourceDraft draft);

    ConnectorDefinition createDraft(ConnectorDraft draft);

    MappingDefinition saveMapping(MappingDraft draft);

    ConnectorTestReport test(String connectorRef);

    ConnectorDefinition publish(String connectorRef, ConnectorTestReport testReport);
}
