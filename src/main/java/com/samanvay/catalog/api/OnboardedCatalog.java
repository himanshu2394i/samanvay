package com.samanvay.catalog.api;

import java.util.List;

/**
 * Read-only view of what manifest onboarding created or adopted (the {@code onboarded} flag), for the staff console. Seeded
 * demo and test rows are not in it. Carries no secrets and no citizen values.
 */
public interface OnboardedCatalog {

    /** Departments whose manifest was onboarded, ordered by code. */
    List<OnboardedDepartment> departments();

    /**
     * @param pinnedKeyThumbprint the manifest signing key pinned at onboarding, null when none
     * @param loginUrl the department's own login address from its identity spec, null when it publishes none
     * @param dataSources its onboarded data sources with their last known health
     * @param documents per category, its onboarded connector with the highest PUBLISHED version (the one serving), or the highest
     *     DRAFT when none is published yet; a newer DRAFT is carried as the document's {@code pendingUpdate}
     * @param journeys onboarded journeys whose requester is this department
     */
    record OnboardedDepartment(
            String code,
            String name,
            String pinnedKeyThumbprint,
            String loginUrl,
            List<DataSourceHealth> dataSources,
            List<OnboardedDocument> documents,
            List<JourneyDefinition> journeys) {}

    /**
     * @param centralSchemaRef the central schema the connector's output follows, null when it names none
     * @param requiredFields the central schema's required fields
     * @param rules the connector's saved mapping, department field to central field; empty when none is saved
     * @param pendingUpdate a DRAFT version newer than the serving one (the live version keeps serving until it is published), else null
     */
    record OnboardedDocument(
            ConnectorDefinition connector,
            String centralSchemaRef,
            List<String> requiredFields,
            List<FieldMapping> rules,
            ConnectorDefinition pendingUpdate) {

        public OnboardedDocument(ConnectorDefinition connector, String centralSchemaRef, List<String> requiredFields, List<FieldMapping> rules) {
            this(connector, centralSchemaRef, requiredFields, rules, null);
        }
    }
}
