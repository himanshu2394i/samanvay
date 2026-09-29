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
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapters;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.FailureKind;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.api.Provenance;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.shared.DataCategory;
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
    private final DepartmentChaos chaos;
    private final BankCheckAdapters bankCheckAdapters;
    private final JsonMapper json = JsonMapper.builder().build();

    ConnectorRuntimeImpl(
            AccessGrantVerifier grantVerifier,
            ConnectorCatalog connectors,
            SchemaCatalog schemas,
            List<ProtocolAdapter> adapterList,
            ResilienceRegistries resilience,
            MappingExecutor mapping,
            AuditService audit,
            DepartmentChaos chaos,
            BankCheckAdapters bankCheckAdapters) {
        this.grantVerifier = grantVerifier;
        this.connectors = connectors;
        this.schemas = schemas;
        this.adapters = new HashMap<>();
        adapterList.forEach(a -> this.adapters.put(a.protocol(), a));
        this.resilience = resilience;
        this.mapping = mapping;
        this.audit = audit;
        this.chaos = chaos;
        this.bankCheckAdapters = bankCheckAdapters;
    }

    @Override
    public ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
        verifyOrAuditAndThrow(grant, inputs.expectedCategory(), grant.connectorRef());
        return executeVerified(grant, capability, inputs);
    }

    /**
     * Grant first, adapter second: a grant that fails verification never reaches
     * the adapter lookup, so no call leaves the platform. When the source answers,
     * one {@code DATA_ACCESSED} row records the access; a failed call (timeout,
     * fault, rejected, not-configured) records nothing, since no data was accessed.
     */
    @Override
    public SourceOutcome<BankCheckAnswer> bankCheck(AccessGrant grant, String sourceCode, BankCheckRequest request) {
        verifyOrAuditAndThrow(grant, DataCategory.BANK_ACCOUNT, sourceCode);
        SourceOutcome<BankCheckAnswer> outcome = bankCheckAdapters
                .forSource(sourceCode)
                .<SourceOutcome<BankCheckAnswer>>map(adapter -> adapter.check(request))
                .orElseGet(() -> new SourceOutcome.SourceFault<>(SourceOutcome.ReasonCode.NOT_CONFIGURED, false));
        if (outcome instanceof SourceOutcome.Answered<BankCheckAnswer>) {
            auditBankCheckAccessed(grant, sourceCode);
        } else if (outcome instanceof SourceOutcome.SourceFault<BankCheckAnswer> fault
                && fault.reasonCode() == SourceOutcome.ReasonCode.MARKER_IN_LIVE_MODE) {
            auditMarkerInLiveMode(grant, sourceCode);
        }
        return outcome;
    }

    /**
     * The source answered, so bank data was accessed. The row records that the
     * check happened for this grant; the answer (account status, name-match
     * verdict) carries no personal data and the holder's name never leaves the
     * adapter, so none of it is put here.
     */
    private void auditBankCheckAccessed(AccessGrant grant, String sourceCode) {
        audit.record(new AuditEntry(
                actorType(grant),
                actorId(grant),
                "DATA_ACCESSED",
                grant.subject().citizenId().toString(),
                sourceCode,
                grant.departmentCode(),
                grant.consentId(),
                grant.id(),
                Outcome.ALLOWED,
                null,
                attribution(grant)));
    }

    /**
     * A LIVE source returned the simulator marker. The adapter has already thrown
     * the answer away (alarming in its logs); here we leave the durable trail.
     */
    private void auditMarkerInLiveMode(AccessGrant grant, String sourceCode) {
        audit.record(new AuditEntry(
                actorType(grant),
                actorId(grant),
                "LIVE_SOURCE_MARKER_REJECTED",
                grant.subject().citizenId().toString(),
                sourceCode,
                grant.departmentCode(),
                grant.consentId(),
                grant.id(),
                Outcome.DENIED,
                "live source returned the simulator marker",
                attribution(grant)));
    }

    private void verifyOrAuditAndThrow(AccessGrant grant, DataCategory category, String connectorRef) {
        try {
            grantVerifier.verifyOrThrow(grant, category, connectorRef);
        } catch (InvalidGrantException e) {
            audit.record(new AuditEntry(
                    actorType(grant),
                    actorId(grant),
                    "GRANT_REJECTED",
                    grant.subject().citizenId().toString(),
                    grant.connectorRef(),
                    grant.departmentCode(),
                    grant.consentId(),
                    grant.id(),
                    Outcome.DENIED,
                    e.getMessage(),
                    attribution(grant)));
            throw e;
        }
    }

    private ConnectorResult executeVerified(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
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
        if (chaos.killed(dataSource.code())) {
            return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
        }
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
                    actorType(grant),
                    actorId(grant),
                    "DATA_ACCESSED",
                    grant.subject().citizenId().toString(),
                    connector.ref(),
                    dataSource.departmentCode(),
                    grant.consentId(),
                    grant.id(),
                    Outcome.ALLOWED,
                    null,
                    attribution(grant)));
            return new ConnectorResult.Success(mapped, provenance);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return new ConnectorResult.Unavailable(FailureKind.BREAKER_OPEN, true);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
        }
    }

    /**
     * Audit attribution comes from the grant, not from this component: the
     * principal (from the caller's token) and the catalog purpose code are
     * both inside the signed grant body. A grant that fails verification may
     * have been tampered with, so for GRANT_REJECTED these values are "as
     * claimed by the rejected grant" - still better than "connector", and the
     * row says DENIED.
     */
    static ActorType actorType(AccessGrant grant) {
        if (grant.principal() == null) {
            return ActorType.SYSTEM;
        }
        return switch (grant.principal().kind()) {
            case CITIZEN -> ActorType.CITIZEN;
            case OFFICER -> ActorType.OFFICER;
            case REVIEWER -> ActorType.REVIEWER;
            case ADMIN -> ActorType.ADMIN;
            case DEPARTMENT -> ActorType.DEPARTMENT;
        };
    }

    static String actorId(AccessGrant grant) {
        return grant.principal() == null ? "unattributed-grant" : grant.principal().id();
    }

    static Map<String, Object> attribution(AccessGrant grant) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("purpose", grant.purpose() == null ? "" : grant.purpose().code());
        meta.put("requester", grant.requester() == null ? "" : grant.requester().id());
        if (grant.principal() != null) {
            meta.put("principalType", grant.principal().kind().name());
        }
        return meta;
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
