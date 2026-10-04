package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.OnboardedCatalog;
import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.domain.JourneyEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.samanvay.catalog.internal.repository.DepartmentRepository;
import com.samanvay.catalog.internal.repository.JourneyRepository;
import com.samanvay.catalog.internal.repository.MappingRepository;
import com.samanvay.catalog.internal.repository.SchemaRepository;
import com.samanvay.shared.DataCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class OnboardedCatalogService implements OnboardedCatalog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DepartmentRepository departments;
    private final DataSourceRepository dataSources;
    private final ConnectorRepository connectors;
    private final JourneyRepository journeyRows;
    private final MappingRepository mappings;
    private final SchemaRepository schemas;
    private final DepartmentCatalog departmentCatalog;
    private final JourneyCatalog journeys;

    OnboardedCatalogService(
            DepartmentRepository departments,
            DataSourceRepository dataSources,
            ConnectorRepository connectors,
            JourneyRepository journeyRows,
            MappingRepository mappings,
            SchemaRepository schemas,
            DepartmentCatalog departmentCatalog,
            JourneyCatalog journeys) {
        this.departments = departments;
        this.dataSources = dataSources;
        this.connectors = connectors;
        this.journeyRows = journeyRows;
        this.mappings = mappings;
        this.schemas = schemas;
        this.departmentCatalog = departmentCatalog;
        this.journeys = journeys;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OnboardedDepartment> departments() {
        List<DataSourceEntity> sources = dataSources.findAll().stream().filter(DataSourceEntity::isOnboarded).toList();
        List<ConnectorEntity> onboardedConnectors = connectors.findAll().stream().filter(ConnectorEntity::isOnboarded).toList();
        Set<String> journeyCodes = journeyRows.findAll().stream().filter(JourneyEntity::isOnboarded).map(JourneyEntity::getCode)
                .collect(Collectors.toSet());
        List<JourneyDefinition> onboardedJourneys = journeys.all().stream().filter(j -> journeyCodes.contains(j.code())).toList();

        return departments.findAll().stream()
                .filter(d -> d.getManifestDigest() != null)
                .sorted(Comparator.comparing(d -> d.getCode()))
                .map(d -> {
                    String code = d.getCode();
                    List<DataSourceEntity> own = sources.stream().filter(s -> code.equals(s.getDepartmentCode())).toList();
                    Set<String> ownCodes = own.stream().map(DataSourceEntity::getCode).collect(Collectors.toSet());
                    return new OnboardedDepartment(
                            code,
                            d.getName(),
                            departmentCatalog.manifestKeyThumbprint(code).orElse(null),
                            departmentCatalog.identity(code).map(DepartmentIdentity::loginUrl).orElse(null),
                            own.stream().map(s -> new DataSourceHealth(s.getCode(), s.getDepartmentCode(), s.getProtocol(), s.getBaseHost(),
                                    s.getHealthStatus() == null ? "UNKNOWN" : s.getHealthStatus(), null)).toList(),
                            documents(onboardedConnectors, ownCodes),
                            onboardedJourneys.stream().filter(j -> code.equals(j.policy().requester())).toList());
                })
                .toList();
    }

    /**
     * Per category, the onboarded connector (from one of the department's own sources) that is SERVING: the highest PUBLISHED
     * version, since that is the one resolution uses; when none is published yet, the highest DRAFT. A DRAFT newer than the serving
     * version is not shown as the document but carried as its pending update.
     */
    private List<OnboardedDocument> documents(List<ConnectorEntity> all, Set<String> sourceCodes) {
        Map<String, List<ConnectorEntity>> byCategory = new LinkedHashMap<>();
        all.stream().filter(c -> sourceCodes.contains(c.getDataSourceCode()))
                .sorted(Comparator.comparing(ConnectorEntity::getDataCategory).thenComparingInt(ConnectorEntity::getVersion))
                .forEach(c -> byCategory.computeIfAbsent(c.getDataCategory(), k -> new ArrayList<>()).add(c));
        List<OnboardedDocument> out = new ArrayList<>();
        for (List<ConnectorEntity> versions : byCategory.values()) {
            ConnectorEntity c = versions.stream().filter(v -> "PUBLISHED".equals(v.getStatus())).reduce((a, b) -> b).orElse(versions.get(versions.size() - 1));
            ConnectorEntity pending = versions.stream().filter(v -> "DRAFT".equals(v.getStatus()) && v.getVersion() > c.getVersion()).reduce((a, b) -> b).orElse(null);
            JsonNode fetch = fetch(c);
            String schemaRef = text(fetch, "output_schema");
            String mappingRef = text(fetch, "mapping_ref");
            List<FieldMapping> rules = mappingRef == null ? List.of() : mappings.findById(mappingRef)
                    .map(m -> CatalogMappingParser.parse(m.getRef(), m.getConnectorRef(), m.getRules()).rules()).orElse(List.of());
            out.add(new OnboardedDocument(definition(c), schemaRef, required(schemaRef), rules, pending == null ? null : definition(pending)));
        }
        return out;
    }

    private List<String> required(String schemaRef) {
        if (schemaRef == null) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        schemas.findById(schemaRef).ifPresent(s -> {
            JsonNode r = JSON.readTree(s.getDefinition()).get("required");
            if (r != null) {
                r.forEach(n -> required.add(n.asString()));
            }
        });
        return required;
    }

    private static JsonNode fetch(ConnectorEntity c) {
        JsonNode caps = c.getCapabilities() == null ? null : JSON.readTree(c.getCapabilities());
        return caps == null ? null : caps.get("FETCH");
    }

    private static String text(JsonNode node, String field) {
        return node == null || node.get(field) == null || !node.get(field).isString() ? null : node.get(field).asString();
    }

    private static ConnectorDefinition definition(ConnectorEntity e) {
        return new ConnectorDefinition(e.getRef(), e.getConnectorId(), e.getVersion(), e.getDataSourceCode(),
                DataCategory.of(e.getDataCategory()), e.getCapabilities(), e.getInputs(), e.getSlaMs(), ConnectorStatus.valueOf(e.getStatus()));
    }
}
