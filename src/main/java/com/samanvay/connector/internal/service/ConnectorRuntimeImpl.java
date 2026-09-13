package com.samanvay.connector.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.FailureKind;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.api.Provenance;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class ConnectorRuntimeImpl implements ConnectorRuntime {

    private final AccessGrantVerifier grantVerifier;
    private final ConnectorCatalog connectors;
    private final SchemaCatalog schemas;
    private final Map<String, ProtocolAdapter> adapters;
    private final ResilienceRegistries resilience;
    private final MappingExecutor mapping;
    private final AuditService audit;
    private final JsonMapper json = JsonMapper.builder().build();

    ConnectorRuntimeImpl(
            AccessGrantVerifier grantVerifier,
            ConnectorCatalog connectors,
            SchemaCatalog schemas,
            List<ProtocolAdapter> adapterList,
            ResilienceRegistries resilience,
            MappingExecutor mapping,
            AuditService audit) {
        this.grantVerifier = grantVerifier;
        this.connectors = connectors;
        this.schemas = schemas;
        this.adapters = new HashMap<>();
        adapterList.forEach(a -> this.adapters.put(a.protocol(), a));
        this.resilience = resilience;
        this.mapping = mapping;
        this.audit = audit;
    }

    @Override
    public ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
        try {
            grantVerifier.verifyOrThrow(grant, inputs.expectedCategory(), grant.connectorRef());
        } catch (InvalidGrantException e) {
            audit.record(new AuditEntry(
                    ActorType.SYSTEM,
                    "connector",
                    "GRANT_REJECTED",
                    grant.subject().citizenId().toString(),
                    grant.connectorRef(),
                    grant.departmentCode(),
                    grant.consentId(),
                    grant.id(),
                    Outcome.DENIED,
                    e.getMessage(),
                    Map.of()));
            throw e;
        }
        ConnectorDefinition connector = connectors.byRef(grant.connectorRef());
        DataSourceDefinition dataSource = connectors.dataSourceFor(connector);
        Map<String, String> bound = bindInputs(connector.inputsJson(), inputs);
        JsonNode caps = json.readTree(connector.capabilitiesJson());
        JsonNode cap = caps.get(capability.name());
        String endpoint = cap != null && cap.get("endpoint") != null ? cap.get("endpoint").asString() : "/";
        String template = cap != null && cap.get("template") != null ? cap.get("template").asString() : null;
        String mappingRef = cap != null && cap.get("mapping_ref") != null ? cap.get("mapping_ref").asString() : null;
        String outputSchema = cap != null && cap.get("output_schema") != null ? cap.get("output_schema").asString() : null;

        var request = new AdapterRequest(
                dataSource.code(),
                dataSource.protocol(),
                dataSource.baseHost(),
                endpoint,
                template,
                bound,
                dataSource.authConfigRef());
        try {
            var adapter = adapters.get(dataSource.protocol());
            if (adapter == null) {
                return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
            }
            var raw = resilience.execute(dataSource.code(), () -> adapter.execute(request));
            var mapped = mappingRef == null ? raw.body() : mapping.apply(connectors.mapping(mappingRef), raw.body());
            if (outputSchema != null) {
                var vr = schemas.validate(outputSchema, mapped);
                if (!vr.valid()) {
                    return new ConnectorResult.Invalid(vr.errors());
                }
            }
            var provenance = new Provenance(
                    dataSource.departmentCode(), Instant.now(), connector.ref(), grant.id(), "REALTIME");
            audit.record(new AuditEntry(
                    ActorType.SYSTEM,
                    "connector",
                    "DATA_ACCESSED",
                    grant.subject().citizenId().toString(),
                    connector.ref(),
                    dataSource.departmentCode(),
                    grant.consentId(),
                    grant.id(),
                    Outcome.ALLOWED,
                    null,
                    Map.of()));
            return new ConnectorResult.Success(mapped, provenance);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return new ConnectorResult.Unavailable(FailureKind.BREAKER_OPEN, true);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
        }
    }

    private Map<String, String> bindInputs(String inputsJson, ExecutionInputs inputs) {
        Map<String, String> bound = new HashMap<>();
        if (inputsJson == null || inputsJson.isBlank()) {
            return bound;
        }
        for (JsonNode spec : json.readTree(inputsJson)) {
            String name = spec.get("name").asString();
            String from = spec.get("from").asString();
            bound.put(name, resolve(from, inputs));
        }
        return bound;
    }

    private static String resolve(String from, ExecutionInputs inputs) {
        if (from.startsWith("link.")) {
            return inputs.link().getOrDefault(from.substring(5), "");
        }
        if (from.startsWith("profile.")) {
            return inputs.profile().getOrDefault(from.substring(8), "");
        }
        if (from.startsWith("journey.var.")) {
            return inputs.journeyVars().getOrDefault(from.substring(12), "");
        }
        return "";
    }

    ResilienceRegistries resilience() {
        return resilience;
    }
}
