package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorNotFoundException;
import com.samanvay.catalog.api.ConnectorNotReadyException;
import com.samanvay.catalog.api.ConnectorPublished;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentRegistered;
import com.samanvay.catalog.api.IllegalConnectorStateException;
import com.samanvay.catalog.api.MappingCatalog;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.api.ValidationResult;
import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.domain.DepartmentEntity;
import com.samanvay.catalog.internal.domain.MappingEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.samanvay.catalog.internal.repository.DepartmentRepository;
import com.samanvay.catalog.internal.repository.JourneyRepository;
import com.samanvay.catalog.internal.repository.MappingRepository;
import com.samanvay.catalog.internal.repository.SchemaRepository;
import com.samanvay.shared.DataCategory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class CatalogServices implements DepartmentCatalog, ConnectorCatalog, SchemaCatalog, CatalogOnboarding {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DepartmentRepository departments;
    private final DataSourceRepository dataSources;
    private final ConnectorRepository connectors;
    private final MappingRepository mappings;
    private final SchemaRepository schemas;
    private final MappingCatalog mappingCatalog;
    private final ApplicationEventPublisher events;
    private final DataSourceHostPolicy hosts;

    @org.springframework.beans.factory.annotation.Autowired
    CatalogServices(
            DepartmentRepository departments,
            DataSourceRepository dataSources,
            ConnectorRepository connectors,
            MappingRepository mappings,
            JourneyRepository journeys,
            SchemaRepository schemas,
            MappingCatalog mappingCatalog,
            ApplicationEventPublisher events) {
        this(departments, dataSources, connectors, mappings, journeys, schemas, mappingCatalog, events, CatalogServices::resolveHost);
    }

    CatalogServices(
            DepartmentRepository departments,
            DataSourceRepository dataSources,
            ConnectorRepository connectors,
            MappingRepository mappings,
            JourneyRepository journeys,
            SchemaRepository schemas,
            MappingCatalog mappingCatalog,
            ApplicationEventPublisher events,
            Function<String, InetAddress> resolver) {
        this.departments = departments;
        this.dataSources = dataSources;
        this.connectors = connectors;
        this.mappings = mappings;
        this.schemas = schemas;
        this.mappingCatalog = mappingCatalog;
        this.events = events;
        this.hosts = new DataSourceHostPolicy(resolver);
    }

    @Override
    public Optional<Department> byCode(String code) {
        return departments.findById(code).map(e -> new Department(e.getCode(), e.getName(), e.getStatus()));
    }

    @Override
    public List<Department> all() {
        return departments.findAll().stream()
                .map(e -> new Department(e.getCode(), e.getName(), e.getStatus()))
                .toList();
    }

    @Override
    @Transactional
    public Department registerDepartment(DepartmentDraft draft) {
        return register(draft);
    }

    @Override
    @Transactional
    public MappingDefinition saveMapping(MappingDraft draft) {
        return mappingCatalog.save(draft);
    }

    @Override
    public ConnectorTestReport test(String connectorRef) {
        ConnectorDefinition connector = byRef(connectorRef);
        dataSourceFor(connector);
        JsonNode caps = JSON.readTree(connector.capabilitiesJson());
        if (caps == null || caps.isNull()) {
            return new ConnectorTestReport(false, List.of("missing capabilities"));
        }
        JsonNode fetch = caps.get("FETCH");
        if (fetch != null && fetch.get("mapping_ref") != null) {
            mapping(fetch.get("mapping_ref").asString());
        }
        return new ConnectorTestReport(true, List.of());
    }

    @Override
    @Transactional
    public Department register(DepartmentDraft draft) {
        DepartmentEntity e = new DepartmentEntity();
        e.setCode(draft.code());
        e.setName(draft.name());
        e.setIdpRealm(draft.idpRealm());
        e.setContactEmail(draft.contactEmail());
        e.setDefaultSlaMs(draft.defaultSlaMs());
        e.setStatus("ACTIVE");
        e.setCreatedAt(Instant.now());
        departments.save(e);
        events.publishEvent(new DepartmentRegistered(draft.code()));
        return new Department(e.getCode(), e.getName(), e.getStatus());
    }

    @Override
    @Transactional
    public DataSourceDefinition registerDataSource(DataSourceDraft draft) {
        hosts.assertAllowed(draft.baseHost());
        DataSourceEntity e = new DataSourceEntity();
        e.setCode(draft.code());
        e.setDepartmentCode(draft.departmentCode());
        e.setProtocol(draft.protocol());
        e.setBaseHost(draft.baseHost());
        e.setAuthType(draft.authType());
        e.setAuthConfigRef(draft.authConfigRef());
        e.setRetryConfig("{\"max\":3}");
        e.setBreakerConfig("{\"failure_rate\":50}");
        e.setHealthStatus("UNKNOWN");
        e.setCreatedAt(Instant.now());
        dataSources.save(e);
        return toDataSource(e);
    }

    @Override
    public Optional<ConnectorDefinition> resolve(String departmentCode, DataCategory category, Capability capability) {
        return dataSources.findAll().stream()
                .filter(ds -> ds.getDepartmentCode().equals(departmentCode))
                .flatMap(ds -> connectors
                        .findByDataSourceCodeAndDataCategoryAndStatus(ds.getCode(), category.code(), "PUBLISHED")
                        .stream())
                .filter(c -> c.getCapabilities().contains("\"" + capability.name() + "\""))
                .max(java.util.Comparator.comparingInt(ConnectorEntity::getVersion))
                .map(this::toConnector);
    }

    @Override
    public ConnectorDefinition byRef(String connectorRef) {
        return connectors
                .findById(connectorRef)
                .map(this::toConnector)
                .orElseThrow(() -> new ConnectorNotFoundException(connectorRef));
    }

    @Override
    public List<ConnectorDefinition> published() {
        return connectors.findByStatus("PUBLISHED").stream().map(this::toConnector).toList();
    }

    @Override
    public DataSourceDefinition dataSourceFor(ConnectorDefinition connector) {
        return dataSources
                .findById(connector.dataSourceCode())
                .map(this::toDataSource)
                .orElseThrow(() -> new ConnectorNotFoundException(connector.ref()));
    }

    @Override
    @Transactional
    public ConnectorDefinition createDraft(ConnectorDraft draft) {
        int version = connectors.findByConnectorId(draft.connectorId()).stream()
                .mapToInt(ConnectorEntity::getVersion)
                .max()
                .orElse(0)
                + 1;
        ConnectorEntity e = new ConnectorEntity();
        e.setRef(draft.connectorId() + "@" + version);
        e.setConnectorId(draft.connectorId());
        e.setVersion(version);
        e.setDataSourceCode(draft.dataSourceCode());
        e.setDataCategory(draft.category().code());
        e.setCapabilities(draft.capabilitiesJson());
        e.setInputs(draft.inputsJson());
        e.setSlaMs(draft.slaMs());
        e.setStatus("DRAFT");
        e.setCreatedAt(Instant.now());
        connectors.save(e);
        return toConnector(e);
    }

    @Override
    @Transactional
    public ConnectorDefinition publish(String connectorRef, ConnectorTestReport testReport) {
        if (!testReport.passed()) {
            throw new ConnectorNotReadyException(connectorRef);
        }
        ConnectorEntity e = connectors.findById(connectorRef).orElseThrow(() -> new ConnectorNotFoundException(connectorRef));
        if (!"DRAFT".equals(e.getStatus())) {
            throw new IllegalConnectorStateException(connectorRef, "only DRAFT can be published");
        }
        e.setStatus("PUBLISHED");
        connectors.save(e);
        events.publishEvent(new ConnectorPublished(connectorRef));
        return toConnector(e);
    }

    @Override
    @Transactional
    public ConnectorDefinition newVersion(String connectorId, ConnectorDraft draft) {
        return createDraft(new ConnectorDraft(
                connectorId,
                draft.dataSourceCode(),
                draft.category(),
                draft.capabilitiesJson(),
                draft.inputsJson(),
                draft.slaMs()));
    }

    @Override
    public MappingDefinition mapping(String mappingRef) {
        MappingEntity e = mappings.findById(mappingRef).orElseThrow(() -> new ConnectorNotFoundException(mappingRef));
        return CatalogMappingParser.parse(e.getRef(), e.getConnectorRef(), e.getRules());
    }

    @Override
    public String definition(String schemaRef) {
        return schemas.findById(schemaRef).map(s -> s.getDefinition()).orElse("{}");
    }

    @Override
    public List<String> refs() {
        return schemas.findAll().stream().map(s -> s.getRef()).toList();
    }

    @Override
    public ValidationResult validate(String schemaRef, JsonNode document) {
        String def = definition(schemaRef);
        JsonNode schema = JSON.readTree(def);
        if (schema.get("required") != null) {
            for (JsonNode req : schema.get("required")) {
                if (document.get(req.asString()) == null || document.get(req.asString()).isNull()) {
                    return new ValidationResult(false, List.of("missing " + req.asString()));
                }
            }
        }
        return ValidationResult.ok();
    }

    private ConnectorDefinition toConnector(ConnectorEntity e) {
        return new ConnectorDefinition(
                e.getRef(),
                e.getConnectorId(),
                e.getVersion(),
                e.getDataSourceCode(),
                DataCategory.of(e.getDataCategory()),
                e.getCapabilities(),
                e.getInputs(),
                e.getSlaMs(),
                ConnectorStatus.valueOf(e.getStatus()));
    }

    private DataSourceDefinition toDataSource(DataSourceEntity e) {
        return new DataSourceDefinition(
                e.getCode(),
                e.getDepartmentCode(),
                e.getProtocol(),
                e.getBaseHost(),
                e.getAuthType(),
                e.getAuthConfigRef(),
                e.getRetryConfig(),
                e.getBreakerConfig());
    }

    private static InetAddress resolveHost(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
