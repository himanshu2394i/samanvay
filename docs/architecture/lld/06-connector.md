# LLD: `connector`

| | |
|---|---|
| **HLD charter** | [hld/06-connector.md](../hld/06-connector.md) |
| **Migration range** | V100–V119 |
| **Package** | `com.samanvay.connector` |
| **Depends on** | `catalog.api`, `consent.api` (`AccessGrantVerifier`), `audit.api` |

---

## 1. A correction this document makes: retry/breaker config moves to the data source

The HLD's example connector JSON put `retry` and `breaker` settings on the **connector**:

```jsonc
{ "id": "rev-income", ..., "sla_ms": 3000, "retry": {...}, "breaker": {...} }
```

But [hld/06-connector.md §7.3](../hld/06-connector.md#7-key-decisions) also states —
correctly, and this is the more important of the two decisions — that
**resilience is keyed on `data_source`, not on connector**: six connectors hitting Revenue
must share one circuit breaker, or Revenue being slow exhausts the thread pool six times
over. Those two statements conflict the moment two connectors on the same data source
specify different `retry`/`breaker` values — there is no coherent answer to "which one's
setting applies to the shared breaker."

**Fix:** `retry` and `breaker` move to `catalog_data_source` (one value per department
server, which is what they actually describe — the server's tolerance, not any one
operation's). `sla_ms` **stays per-connector-capability**, since two operations against
the same server can legitimately have different expected latencies (`getIncomeCert` vs.
`verifyIncomeCert`). This is reflected in [lld/02-catalog.md](02-catalog.md)'s DDL, not
`catalog_connector`'s.

## 2. Migration: `V100__connector_init.sql`

```sql
CREATE TABLE connector_submission_attempt (
    id                    VARCHAR(100) PRIMARY KEY,   -- the idempotency key - see HLD §7.5
    grant_id              UUID NOT NULL,               -- soft ref to consent_access_grant
    workflow_instance_id  VARCHAR(100) NOT NULL,       -- soft ref to Flowable's process instance
    connector_ref         VARCHAR(100) NOT NULL,
    request_hash          VARCHAR(64) NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    external_reference    VARCHAR(200),
    response_snapshot     JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ
);

CREATE TABLE connector_batch_file (
    id                UUID PRIMARY KEY,
    data_source_code  VARCHAR(60)  NOT NULL,
    filename          VARCHAR(300) NOT NULL,
    checksum          VARCHAR(64)  NOT NULL,
    file_timestamp    TIMESTAMPTZ  NOT NULL,
    row_offset        INT NOT NULL DEFAULT 0,
    rows_total        INT,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETE','FAILED')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    UNIQUE (data_source_code, checksum)      -- the re-delivery guard, enforced, not just checked in code
);

CREATE TABLE connector_exception (
    id                UUID PRIMARY KEY,
    data_source_code  VARCHAR(60) NOT NULL,
    connector_ref     VARCHAR(100),
    source_file       VARCHAR(300),
    raw_content       TEXT NOT NULL,
    violations        JSONB NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(100)
);
```

## 3. `ProtocolAdapter` — four implementations, one SPI

```java
package com.samanvay.connector.api;

public interface ProtocolAdapter {
    String protocol();
    AdapterResponse execute(AdapterRequest request);
}
```

### 3.1 `SoapAdapter` — XXE closed, every substitution escaped

```java
package com.samanvay.connector.internal.protocol;

@Component
class SoapAdapter implements ProtocolAdapter {

    private final DocumentBuilderFactory dbf;

    SoapAdapter() {
        dbf = DocumentBuilderFactory.newInstance();
        // A department's SOAP response is untrusted input (HLD §9.1/§9.3).
        // Without these three lines, a malicious or compromised department
        // could smuggle a local file read or SSRF via an external XML
        // entity. This is not defensive overkill - it is the specific,
        // named threat in the HLD's threat model.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
    }

    @Override public String protocol() { return "SOAP"; }

    @Override
    public AdapterResponse execute(AdapterRequest req) {
        String envelope = renderTemplate(req.template(), req.boundInputs());
        byte[] raw = httpClient.post(req.endpoint(), envelope, req.timeout(), req.authConfig());
        Document doc = parseSafely(raw);   // uses the hardened dbf above - never DocumentBuilderFactory.newInstance() elsewhere
        return new AdapterResponse(XmlToJson.convert(doc), raw.length);
    }

    private String renderTemplate(String template, Map<String, String> inputs) {
        String rendered = template;
        for (var e : inputs.entrySet())
            rendered = rendered.replace("{{" + e.getKey() + "}}", escapeXml(e.getValue()));
        return rendered;
    }

    // Hand-written, not a dependency: five characters, no library earns its
    // keep for this. A name containing & or < must not be able to break
    // out of the element it's substituted into.
    private static String escapeXml(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
```

### 3.2 `JdbcAdapter` — the one a security reviewer will read hardest

```java
package com.samanvay.connector.internal.protocol;

@Component
class JdbcAdapter implements ProtocolAdapter {

    private static final Pattern SELECT_ONLY = Pattern.compile("^\\s*SELECT\\s", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FORBIDDEN = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "GRANT", ";--", "/*");

    @Override public String protocol() { return "JDBC"; }

    @Override
    public AdapterResponse execute(AdapterRequest req) {
        String sql = req.renderedStatement();
        if (!SELECT_ONLY.matcher(sql).find() || containsForbidden(sql))
            throw new IllegalConnectorConfigurationException(
                "JDBC adapter permits parameterized SELECT only: " + req.connectorRef());

        try (var conn = readOnlyDataSource(req.dataSourceCode()).getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(1000);   // row-count cap - a misconfigured query cannot flood the pipeline
            bindParameters(ps, req.boundInputs());   // bound params only, never string-built SQL
            try (var rs = ps.executeQuery()) {
                return new AdapterResponse(ResultSetToJson.convert(rs), -1);
            }
        }
    }

    private boolean containsForbidden(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return FORBIDDEN.stream().anyMatch(upper::contains);
    }
}
```

This is defense in depth, not the only layer: `readOnlyDataSource(...)` connects using a
credential the *department* is expected to have provisioned as read-only on their end
(`catalog_data_source.auth_config_ref`). Our keyword filter guards against a
misconfigured connector definition; the department's own grant guards against a
compromised one. Neither substitutes for the other.

### 3.3 `RestAdapter`, `SftpCsvAdapter`

Bound parameters are URL-encoded (`RestAdapter`, via `URLEncoder`, never concatenated into
a path segment) and host-key verification is on for SFTP (`SftpCsvAdapter`, via
`StrictHostKeyChecking=yes` equivalent in the SSHD client config) — both stated as
non-negotiable in the HLD, neither has a design wrinkle worth expanding on further here.

## 4. `ConnectorRuntime` — the full pipeline

```java
package com.samanvay.connector.internal.service;

@Component
class ConnectorRuntimeImpl implements ConnectorRuntime {

    private final AccessGrantVerifier grantVerifier;      // consent.api - public key only
    private final ConnectorCatalog connectors;            // catalog.api
    private final Map<String, ProtocolAdapter> adapters;  // keyed by protocol() string
    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;
    private final RetryRegistry retries;
    private final MappingExecutor mapping;
    private final DataQuality dataQuality;
    private final AuditService audit;

    @Override
    public ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
        grantVerifier.verifyOrThrow(grant, inputs.expectedCategory(), grant.connectorRef());

        var connector = connectors.byRef(grant.connectorRef());
        var capConfig = connector.capabilities().get(capability);
        var dataSource = connectors.dataSourceFor(connector);

        var boundInputs = bindInputs(connector.inputs(), inputs);   // fixed context only - link.*, profile.*, grant.*, journey.var.*
        var request = new AdapterRequest(dataSource, capConfig.endpoint(), capConfig.template(),
            boundInputs, dataSource.authConfigRef());

        // Keyed on data source, per §1 - NOT on connector.id() or capability.
        var breaker = breakers.circuitBreaker(dataSource.code());
        var bulkhead = bulkheads.bulkhead(dataSource.code());
        var retry = retries.retry(dataSource.code());

        try {
            var raw = Decorators.ofSupplier(() -> adapters.get(dataSource.protocol()).execute(request))
                .withCircuitBreaker(breaker).withBulkhead(bulkhead).withRetry(retry)
                .decorate().get();

            if (raw.sizeBytes() > MAX_RESPONSE_BYTES)
                return new ConnectorResult.Unavailable(FailureKind.RESPONSE_TOO_LARGE, false);

            if (matchesAny(raw.body(), capConfig.errorPaths()))
                return classifyRemoteFault(raw.body());   // NotFound (record absent) vs Unavailable(REMOTE_FAULT) - see §6

            var dq = dataQuality.validate(raw.body(), capConfig.outputSchema());
            if (!dq.isValid()) { exceptions.record(connector, dq); return new ConnectorResult.Invalid(dq.violations()); }

            var canonical = mapping.apply(connectors.mapping(capConfig.mappingRef()), raw.body());
            connectors.schemaCatalog().validate(capConfig.outputSchema(), canonical);   // config defect if this throws, not a runtime fault

            var provenance = new Provenance(dataSource.departmentCode(), Instant.now(),
                connector.refWithVersion(), grant.id(), dataSource.freshnessMode());

            audit.record(dataAccessedEntry(grant, connector));
            return new ConnectorResult.Success(canonical, provenance);

        } catch (CallNotPermittedException e) {
            return new ConnectorResult.Unavailable(FailureKind.BREAKER_OPEN, true);
        } catch (java.util.concurrent.TimeoutException e) {
            return new ConnectorResult.Unavailable(FailureKind.TIMEOUT, true);
        }
    }
}
```

`NotFound` vs. `Unavailable(REMOTE_FAULT)` in `classifyRemoteFault` matters concretely:
the connector definition's `error_paths` entries can each be tagged whether a match means
"the record genuinely does not exist" (e.g. a SOAP fault `NO_RECORD_FOUND`) or "the
department failed to answer" (e.g. `INTERNAL_ERROR`). Conflating them either denies a
citizen who has no income certificate, or retries forever against a department that will
never have the record.

## 5. Mapping DSL executor — the fixed registry, literally

```java
package com.samanvay.connector.internal.mapping;

@Component
class MappingExecutor {

    // This Map IS the entire security boundary from HLD §5.2. There is no
    // code path - no reflection, no class loading, no scripting engine -
    // by which a mapping definition can invoke anything not listed here.
    // Adding a transform means adding a line to this map and redeploying;
    // it can never mean a department or an admin UI adding arbitrary code.
    //
    // REGISTRY.keySet() must exactly equal shared.MappingTransforms.NAMES
    // (asserted by RegistryMatchesSharedNamesTest, §10) - catalog validates
    // mapping definitions against that same shared set at save time
    // (see lld/02-catalog.md), and the two must never drift independently.
    private static final Map<String, TransformFunction> REGISTRY = Map.of(
        "trim",       (v, args) -> v.trim(),
        "upper",      (v, args) -> v.toUpperCase(Locale.ROOT),
        "lower",      (v, args) -> v.toLowerCase(Locale.ROOT),
        "date_parse", (v, args) -> LocalDate.parse(v, DateTimeFormatter.ofPattern(args.get(0))).toString(),
        "coalesce",   (v, args) -> v != null ? v : args.get(0),
        "split_name", MappingFunctions::splitName,
        "lookup",     MappingFunctions::lookupTable,
        "mask",       (v, args) -> MappingFunctions.maskExcept(v, Integer.parseInt(args.get(0)))
    );

    JsonNode apply(MappingDefinition def, JsonNode source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (FieldMapping rule : def.rules()) {
            String value = JsonPath.<String>read(source, rule.source());
            for (var t : rule.transforms()) {
                var fn = REGISTRY.get(t.fn());
                if (fn == null) throw new UnknownTransformException(t.fn());   // also rejected at mapping SAVE time, see catalog LLD
                value = fn.apply(value, t.args());
            }
            JsonPointerUtil.setNested(result, rule.target(), value);   // "person.dob" -> nested field
        }
        return result;
    }
}
```

## 6. `SUBMIT` idempotency

```java
@Override
public ConnectorResult submit(AccessGrant grant, ExecutionInputs inputs) {
    grantVerifier.verifyOrThrow(grant, inputs.expectedCategory(), grant.connectorRef());

    // Derived from the grant for the MVP - see hld/06-connector.md §7.5:
    // this is intentionally its own concept, not reused as "the" key
    // forever, in case one grant ever needs to cover more than one
    // submission.
    String idempotencyKey = grant.id().toString();

    var existing = submissions.find(idempotencyKey);
    if (existing.isPresent() && existing.get().status() == SubmissionStatus.SUCCEEDED) {
        // A retried SUBMIT after a timeout resolves to the ORIGINAL
        // result. The department receives one submission, not two -
        // this is the fix for the single most likely real bug in the
        // system, and it costs one table.
        return new ConnectorResult.Success(existing.get().responseSnapshot(), existing.get().provenance());
    }

    var attempt = submissions.startOrResume(idempotencyKey, grant.id(),
        inputs.workflowInstanceId(), grant.connectorRef());
    var result = executeSubmitCapability(grant, inputs);
    if (result instanceof ConnectorResult.Success s) {
        submissions.markSucceeded(attempt.id(), extractExternalReference(s), s.canonical());
    }
    return result;
}
```

## 7. Batch ingestion

```java
package com.samanvay.connector.internal.service;

@Component
class SftpBatchIngestor implements BatchIngestor {

    @Scheduled(cron = "0 0 2 * * *")   // 02:00 daily
    void ingestAll() {
        catalog.batchDataSources().forEach(ds -> ingest(ds.code()));
    }

    @Override
    public BatchResult ingest(String dataSourceCode) {
        var ds = catalog.dataSourceFor(dataSourceCode);
        for (RemoteFile file : sftp.listNewFiles(ds)) {
            String checksum = sha256Hex(file.bytes());
            // UNIQUE (data_source_code, checksum) in the DDL enforces
            // this, not just this check - the check is the fast path,
            // the constraint is what actually prevents re-processing
            // under a race (two ingest runs overlapping).
            if (batchFiles.existsByChecksum(dataSourceCode, checksum)) continue;

            var job = batchFiles.startJob(dataSourceCode, file.name(), checksum, file.lastModified());
            try (var rows = csv.stream(file.bytes())) {
                rows.skip(job.resumeOffset()).forEach(row -> {
                    var dq = dataQuality.validate(row, ds.rowSchema());
                    if (dq.isValid())
                        events.publishEvent(new BatchRowIngested(dataSourceCode, row, file.lastModified()));
                    else
                        exceptions.recordBatchRow(dataSourceCode, file.name(), row, dq.violations());
                    batchFiles.advanceOffset(job.id());   // committed per row - a crash mid-file resumes, doesn't restart
                });
            }
            batchFiles.markComplete(job.id());
        }
        events.publishEvent(new BatchIngestCompleted(dataSourceCode));
        return new BatchResult(dataSourceCode);
    }
}
```

`registry` consumes `BatchRowIngested`/`BatchIngestCompleted` — see
[LLD.md §7.4](../LLD.md#74-batch-ingestion-hld-64) for that side of the flow.

## 8. Error handling

| Exception (`connector.api`) | Raised when |
|---|---|
| `IllegalConnectorConfigurationException` | A connector definition itself is broken — non-`SELECT` JDBC statement, unknown mapping transform, output fails its own declared schema. Always a configuration defect, never a runtime/department fault — surfaced to `INTEGRATION_ADMIN`, never retried |
| `UnknownTransformException` | A mapping references a transform not in the fixed registry — also caught earlier, at mapping save time in `catalog` |

`ConnectorResult` (§4) is the primary error-signaling mechanism for anything that **can**
legitimately happen at runtime (timeout, breaker open, record not found, bad data) — these
are not exceptions, because `orchestration` needs to branch on them as ordinary values,
not catch them. See [LLD.md §4.3](../LLD.md#43-what-is-never-caught-and-swallowed).

## 9. Events

| Event | Payload |
|---|---|
| `DataAccessed` | `{grantId, connectorRef, dataSourceCode}` — observability only; the audit entry itself is written synchronously in §4, not via this event |
| `ConnectorFailed` | `{connectorRef, dataSourceCode, failureKind}` |
| `CircuitBreakerOpened` / `Closed` | `{dataSourceCode}` |
| `BatchIngestCompleted` | `{dataSourceCode}` |
| `BatchRowIngested` | `{dataSourceCode, row, asOf}` |
| `BatchRowRejected` | `{dataSourceCode, filename, violations}` |

## 10. Tests

| Test | Proves |
|---|---|
| `SoapAdapterXxeTest` | A response containing `<!DOCTYPE ... [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>` does **not** cause a file read |
| `SoapAdapterEscapingTest` | A name containing `&`, `<`, `"` round-trips correctly through template rendering |
| `JdbcAdapterGuardTest` | `INSERT`, `DELETE`, `"; DROP TABLE"` and a bare non-`SELECT` statement are all rejected before any connection is opened |
| `SharedResilienceInstanceTest` | Two `ConnectorDefinition`s with the same `dataSourceCode` resolve to the **same** `CircuitBreaker` object instance from the registry — the test that actually proves §1/§4's key design point, not just documents it |
| `MappingExecutorTest` | Every registry function; an unknown function name throws `UnknownTransformException` rather than being silently ignored |
| `RegistryMatchesSharedNamesTest` | `MappingExecutor.REGISTRY.keySet()` exactly equals `shared.MappingTransforms.NAMES` — the test that keeps `catalog`'s validation and `connector`'s execution from silently drifting apart |
| `SubmitIdempotencyIT extends PostgresIntegrationTest` | Calling `submit()` twice with the same grant produces one `SUCCEEDED` `connector_submission_attempt` row and the second call returns the first's `external_reference`, not a new one |
| `BatchReDeliveryIT extends PostgresIntegrationTest` | Ingesting the same file twice (same checksum) processes it once; a job killed mid-file resumes from `row_offset`, not from zero |
| `ConnectorContractTest` (WireMock, per connector, no live mock department) | A recorded department response maps to the exact expected canonical output — catches a department silently renaming a field |
