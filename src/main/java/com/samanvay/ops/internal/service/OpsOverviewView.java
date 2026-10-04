package com.samanvay.ops.internal.service;

import java.time.Instant;
import java.util.List;

/**
 * What the staff console shows about the departments that were really onboarded from manifests: their sources, documents
 * (with the mapping onto the central schema) and journeys. Read-only; no secrets, no citizen values (docs/contracts/ops-overview.md).
 */
public record OpsOverviewView(Instant generatedAt, List<Department> departments) {

    public record Department(
            String code,
            String name,
            String pinnedKeyThumbprint,
            String loginUrl,
            List<DataSource> dataSources,
            List<Document> documents,
            List<Journey> journeys) {}

    /** @param health GREEN, AMBER, RED or UNKNOWN (the last probe) */
    public record DataSource(String code, String protocol, String host, String health, String healthDetail) {}

    /**
     * @param connectorStatus DRAFT or PUBLISHED
     * @param working the connector is published and its source is not RED
     * @param unmappedRequired required central fields no mapping rule fills
     */
    public record Document(
            String category,
            String title,
            String connectorRef,
            String connectorStatus,
            String dataSourceCode,
            String sourceHealth,
            Trial lastTrial,
            boolean working,
            String centralSchemaRef,
            List<Mapping> mappings,
            List<String> unmappedRequired) {}

    public record Trial(Instant at, String outcome) {}

    public record Mapping(String source, String target, boolean required) {}

    /** @param ready every required category has a published connector */
    public record Journey(String code, String name, String status, boolean ready, List<Need> needs, Counts counts) {}

    public record Need(String category, String department, boolean working) {}

    public record Counts(long running, long completed, long failed, long last7Days) {}
}
