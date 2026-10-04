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
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
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
import com.samanvay.shared.OpsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.connector.internal.protocol.ExchangeDeadline;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
    private final java.time.Duration totalTimeout;
    private final BankCheckAdapters bankCheckAdapters;
    private final MeterRegistry meters;
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
            @org.springframework.beans.factory.annotation.Value("${samanvay.connector.total-timeout:PT10S}")
                    java.time.Duration totalTimeout,
            BankCheckAdapters bankCheckAdapters,
            MeterRegistry meters) {
        this.totalTimeout = totalTimeout;
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
        this.meters = meters;
    }

    @Override
    public ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
        verifyOrAuditAndThrow(grant, inputs.expectedCategory(), grant.connectorRef());
        return executeVerified(grant, capability, inputs, false);
    }

    @Override
    public ConnectorResult trial(String connectorRef, String samplePersonId, com.samanvay.shared.PrincipalRef by) {
        com.samanvay.shared.InvalidRequestException.requireText(samplePersonId, "samplePersonId");
        ConnectorDefinition connector = connectors.byRef(connectorRef);
        String department = connectors.dataSourceFor(connector).departmentCode();
        String person = samplePersonId.trim();
        String declared = declaredSample(connector);
        if (declared != null && !declared.equals(person) && !shape(declared).equals(shape(person))) {
            // A trial reads the department's real service. With a published FAKE sample, only that sample or an id of the same
            // shape may be tried, so the trial endpoint is not a way to look up an arbitrary citizen without consent.
            throw new com.samanvay.shared.InvalidRequestException("The person ID does not look like the department's published sample (" + declared
                    + "): a trial is run for the sample person or an ID of the same form");
        }
        String subject = person.equals(declared) ? "sample:" + person : "person:" + fingerprint(person);
        // A synthetic, never-stored grant only so the same fetch path runs; it names no real citizen.
        var grant = new AccessGrant(java.util.UUID.randomUUID(), new byte[0], java.util.UUID.randomUUID(), 0,
                new com.samanvay.shared.SubjectRef(java.util.UUID.randomUUID()), null, connector.category(), department, connectorRef, null, by,
                Instant.now(), Instant.now().plusSeconds(60), new byte[0]);
        var inputs = new ExecutionInputs(connector.category(), "trial", Map.of("personId", person, "localIdToken", person, "localIdType", "SAMPLE"),
                Map.of(), Map.of());
        Outcome outcome = Outcome.DENIED;
        try {
            ConnectorResult result = executeVerified(grant, Capability.FETCH, inputs, true);
            outcome = result instanceof ConnectorResult.Success ? Outcome.ALLOWED : Outcome.DENIED;
            return result;
        } finally {
            audit.record(new AuditEntry(ActorType.ADMIN, by.id(), "CONNECTOR_TRIAL", subject, connectorRef, department, null, null, outcome, null, Map.of()));
        }
    }

    /**
     * No record at all: nothing came back, or every field is a JSON null. An SFTP source with no matching row and a JDBC source with
     * no row both answer with an empty object; that is "the department holds nothing", not a success with nothing in it.
     */
    static boolean isEmptyRecord(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return true;
        }
        if (!body.isObject()) {
            return false;
        }
        for (JsonNode v : body.values()) {
            if (!v.isNull()) {
                return false;
            }
        }
        return true;
    }

    /** The fake person the department's manifest published for trials ({@code sample_person_id}), or null. */
    private String declaredSample(ConnectorDefinition connector) {
        JsonNode fetch = json.readTree(connector.capabilitiesJson()).get("FETCH");
        JsonNode s = fetch == null ? null : fetch.get("sample_person_id");
        return s == null || s.asString().isBlank() ? null : s.asString();
    }

    /** "DBT-1001" and "DBT-9999" have the same shape; "NOBODY" does not: runs of letters, runs of digits, other characters kept. */
    static String shape(String id) {
        return id.trim().replaceAll("[A-Za-z]+", "a").replaceAll("[0-9]+", "0");
    }

    /** A short one-way fingerprint, so the audit trail can tell two trials of one person apart without holding the person ID. */
    private static String fingerprint(String person) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(person.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        long started = System.nanoTime();
        SourceOutcome<BankCheckAnswer> outcome = bankCheckAdapters
                .forSource(sourceCode)
                .<SourceOutcome<BankCheckAnswer>>map(adapter -> adapter.check(request))
                .orElseGet(() -> new SourceOutcome.SourceFault<>(SourceOutcome.ReasonCode.NOT_CONFIGURED, false));
        OpsMetrics.recordConnectorExchange(meters, sourceCode, bankCheckMetricOutcome(outcome), System.nanoTime() - started);
        if (outcome instanceof SourceOutcome.Answered<BankCheckAnswer>) {
            auditBankCheckAccessed(grant, sourceCode);
        } else if (outcome instanceof SourceOutcome.SourceFault<BankCheckAnswer> fault
                && fault.reasonCode() == SourceOutcome.ReasonCode.MARKER_IN_LIVE_MODE) {
            auditMarkerInLiveMode(grant, sourceCode);
        }
        return outcome;
    }

    /** Ops-metric outcome of a bank-check call: an answer (even a negative one) is a success; not-called is unavailable. */
    private static String bankCheckMetricOutcome(SourceOutcome<BankCheckAnswer> outcome) {
        if (outcome instanceof SourceOutcome.Answered<BankCheckAnswer>) {
            return OpsMetrics.OUTCOME_SUCCESS;
        }
        if (outcome instanceof SourceOutcome.SourceFault<BankCheckAnswer> fault
                && (fault.reasonCode() == SourceOutcome.ReasonCode.NOT_CONFIGURED
                        || fault.reasonCode() == SourceOutcome.ReasonCode.CREDENTIAL_MISSING)) {
            return OpsMetrics.OUTCOME_UNAVAILABLE;
        }
        return OpsMetrics.OUTCOME_FAILURE;
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

    private ConnectorResult executeVerified(AccessGrant grant, Capability capability, ExecutionInputs inputs, boolean trial) {
        ConnectorDefinition connector = connectors.byRef(grant.connectorRef());
        DataSourceDefinition dataSource = connectors.dataSourceFor(connector);
        Map<String, String> bound = bindInputs(connector.inputsJson(), inputs);
        JsonNode caps = json.readTree(connector.capabilitiesJson());
        JsonNode cap = caps.get(capability.name());
        String endpoint = cap != null && cap.get("endpoint") != null ? cap.get("endpoint").asString() : "/";
        String template = cap != null && cap.get("template") != null ? cap.get("template").asString() : null;
        String mappingRef = cap != null && cap.get("mapping_ref") != null ? cap.get("mapping_ref").asString() : null;
        String outputSchema = cap != null && cap.get("output_schema") != null ? cap.get("output_schema").asString() : null;
        JsonNode resolveSpec = cap == null ? null : cap.get("resolve");

        var request = new AdapterRequest(
                dataSource.code(),
                dataSource.protocol(),
                dataSource.baseHost(),
                endpoint,
                template,
                bound,
                dataSource.authConfigRef(),
                dataSource.authType(),
                dataSource.authSpecJson(),
                accessDetails(cap));
        if (chaos.killed(dataSource.code())) {
            OpsMetrics.countConnectorCall(meters, dataSource.code(), OpsMetrics.OUTCOME_UNAVAILABLE);
            return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
        }
        try {
            var adapter = adapters.get(dataSource.protocol());
            if (adapter == null) {
                OpsMetrics.countConnectorCall(meters, dataSource.code(), OpsMetrics.OUTCOME_UNAVAILABLE);
                return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
            }
            com.samanvay.connector.api.AdapterResponse raw;
            // One total deadline for the whole exchange, every retry attempt included.
            try (ExchangeDeadline deadline = ExchangeDeadline.start(totalTimeout)) {
                raw = timedExchange(dataSource.code(), () -> resolveThenExecute(adapter, request, resolveSpec));
            }
            if (raw == NO_DOCUMENT || isEmptyRecord(raw.body())) {
                // The department answered, but holds no document for this person: nothing was accessed.
                return new ConnectorResult.NotFound("the department holds no such document for this person");
            }
            var mapped = mappingRef == null ? raw.body() : mapping.apply(connectors.mapping(mappingRef), raw.body());
            if (outputSchema != null) {
                var vr = schemas.validate(outputSchema, mapped);
                if (!vr.valid()) {
                    return new ConnectorResult.Invalid(vr.errors());
                }
            }
            var provenance = new Provenance(
                    dataSource.departmentCode(), Instant.now(), connector.ref(), grant.id(), "REALTIME");
            if (!trial) { // a trial involves no citizen: nothing was accessed on anyone's behalf
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
            }
            return new ConnectorResult.Success(mapped, provenance);
        } catch (com.samanvay.connector.internal.protocol.ResponseTooLargeException e) {
            return new ConnectorResult.Unavailable(FailureKind.RESPONSE_TOO_LARGE, true);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return new ConnectorResult.Unavailable(FailureKind.BREAKER_OPEN, true);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            return new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true);
        }
    }

    /**
     * The resilience-wrapped adapter exchange (every retry attempt), timed per data source. The
     * outcome is the exchange's: it returned (success), a breaker or bulkhead refused it
     * (unavailable), or it threw (failure). Exceptions propagate exactly as before.
     */
    private com.samanvay.connector.api.AdapterResponse timedExchange(
            String source, Supplier<com.samanvay.connector.api.AdapterResponse> call) {
        long started = System.nanoTime();
        String outcome = OpsMetrics.OUTCOME_FAILURE;
        try {
            var response = resilience.execute(source, call);
            outcome = OpsMetrics.OUTCOME_SUCCESS;
            return response;
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException
                | io.github.resilience4j.bulkhead.BulkheadFullException e) {
            outcome = OpsMetrics.OUTCOME_UNAVAILABLE;
            throw e;
        } finally {
            OpsMetrics.recordConnectorExchange(meters, source, outcome, System.nanoTime() - started);
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

    /** Capability keys the runtime itself uses; every other textual key is protocol-specific access detail. */
    private static final java.util.Set<String> RESERVED_CAPABILITY_KEYS =
            java.util.Set.of("endpoint", "template", "mapping_ref", "output_schema", "resolve");

    /** Returned from the exchange when the resolve step found the person holds no document (compared by identity). */
    private static final com.samanvay.connector.api.AdapterResponse NO_DOCUMENT =
            new com.samanvay.connector.api.AdapterResponse(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), 0);

    /**
     * The optional resolve step (docs/FINAL-CHANGES.md section 12). When the connector's capability declares
     * {@code resolve}, a department that keys each document separately is asked first "which documents does this
     * person hold?" (person ID in, document keys out); one key is chosen and bound into the document call. Without
     * it, the document call goes out as it always did, with the person ID as the key. Both calls run inside the one
     * resilience-wrapped, deadline-bounded exchange, so ops metrics count them as a single exchange.
     *
     * <p>Only the inputs the resolve path names ({@code {personId}}) go to the resolve call, never every journey
     * variable. REST only (the path is filled and queried by the REST adapter).
     */
    private static com.samanvay.connector.api.AdapterResponse resolveThenExecute(
            ProtocolAdapter adapter, AdapterRequest request, JsonNode resolve) {
        if (resolve == null || !resolve.isObject()) {
            return adapter.execute(request);
        }
        String path = textOf(resolve, "path", null);
        String into = textOf(resolve, "into", "key");
        if (path == null) {
            throw new IllegalConnectorConfigurationException("the connector's resolve step has no path");
        }
        if (request.boundInputs().containsKey(into)) {
            throw new IllegalConnectorConfigurationException(
                    "the connector's resolve step would overwrite the input '" + into + "' it already binds");
        }
        // The resolve call is its own request: it never inherits the document call's method or body (a POST document with a
        // GET resolve must not turn the resolve into a POST). A POST resolve says which inputs travel in its body.
        Map<String, String> resolveAccess = new HashMap<>(request.access());
        resolveAccess.remove("method");
        resolveAccess.remove("body_inputs");
        String resolveMethod = textOf(resolve, "method", null);
        String resolveBody = textOf(resolve, "body_inputs", "");
        if (resolveMethod != null) {
            resolveAccess.put("method", resolveMethod);
        }
        if (!resolveBody.isEmpty()) {
            resolveAccess.put("body_inputs", resolveBody);
        }
        java.util.Set<String> bodyNames = java.util.Arrays.stream(resolveBody.split(",")).map(String::trim).collect(java.util.stream.Collectors.toSet());
        Map<String, String> resolveInputs = new HashMap<>();
        request.boundInputs().forEach((name, value) -> {
            if (path.contains("{" + name + "}") || bodyNames.contains(name)) {
                resolveInputs.put(name, value);
            }
        });
        var answer = adapter.execute(new AdapterRequest(request.dataSourceCode(), request.protocol(), request.host(), path, null,
                resolveInputs, request.authConfigRef(), request.authType(), request.authSpecJson(), resolveAccess));
        JsonNode list = answer.body() == null ? null : answer.body().get(textOf(resolve, "list_field", "documents"));
        if (list == null || !list.isArray() || list.isEmpty()) {
            return NO_DOCUMENT;
        }
        JsonNode chosen = list.get(0);
        if ("latest".equals(textOf(resolve, "select", "latest"))) {
            String latestField = textOf(resolve, "latest_field", "latest");
            for (JsonNode item : list) {
                if (item.get(latestField) != null && item.get(latestField).asBoolean(false)) {
                    chosen = item;
                    break;
                }
            }
        }
        JsonNode key = chosen.get(textOf(resolve, "key_field", "key"));
        if (key == null || key.asString().isBlank()) {
            return NO_DOCUMENT;
        }
        Map<String, String> bound = new HashMap<>(request.boundInputs());
        bound.put(into, key.asString());
        return adapter.execute(new AdapterRequest(request.dataSourceCode(), request.protocol(), request.host(), request.endpoint(),
                request.template(), bound, request.authConfigRef(), request.authType(), request.authSpecJson(), request.access()));
    }

    private static String textOf(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? fallback : v.asString();
    }

    /** Protocol-specific, non-secret details the connector declares on its capability (e.g. key_column, view). */
    private static Map<String, String> accessDetails(JsonNode cap) {
        Map<String, String> out = new HashMap<>();
        if (cap != null && cap.isObject()) {
            cap.properties().forEach(e -> {
                if (!RESERVED_CAPABILITY_KEYS.contains(e.getKey()) && e.getValue().isString()) {
                    out.put(e.getKey(), e.getValue().asString());
                }
            });
        }
        return out;
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
