# LLD: `catalog`

| | |
|---|---|
| **HLD charter** | [hld/02-catalog.md](../hld/02-catalog.md) |
| **Migration range** | V20–V39 |
| **Package** | `com.samanvay.catalog` |
| **Depends on** | `audit.api`, `shared` |

---

## 1. Migration: `V20__catalog_init.sql`

```sql
CREATE TABLE catalog_department (
    code            VARCHAR(60) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    idp_realm       VARCHAR(100),
    contact_email   VARCHAR(200),
    default_sla_ms  INT,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_data_source (
    code             VARCHAR(60) PRIMARY KEY,
    department_code  VARCHAR(60) NOT NULL REFERENCES catalog_department(code),
    protocol         VARCHAR(20) NOT NULL CHECK (protocol IN ('REST','SOAP','SFTP_CSV','JDBC')),
    -- Host only. Connector definitions supply paths, never hosts - the
    -- SSRF mitigation in HLD §9.3 depends on the host being admin-set and
    -- allowlisted here, separately from anything a connector can specify.
    base_host        VARCHAR(300) NOT NULL,
    auth_type        VARCHAR(30) NOT NULL,
    auth_config_ref  VARCHAR(200) NOT NULL,      -- SecretStore pointer, never a credential
    -- Moved here from the connector, per lld/06-connector.md §1: these
    -- describe the SERVER's tolerance, and six connectors on one data
    -- source must share one circuit breaker.
    retry_config     JSONB NOT NULL DEFAULT '{"max":3,"backoff":"exponential","base_ms":500}',
    breaker_config   JSONB NOT NULL DEFAULT '{"failure_rate":50,"window":20,"open_seconds":30}',
    health_status    VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' CHECK (health_status IN ('GREEN','AMBER','RED','UNKNOWN')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_schema (
    ref         VARCHAR(120) PRIMARY KEY,    -- e.g. "Credential/IncomeCertificate@1"
    name        VARCHAR(100) NOT NULL,
    version     INT NOT NULL,
    definition  JSONB NOT NULL,              -- the JSON Schema document itself
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_connector (
    ref               VARCHAR(120) PRIMARY KEY,   -- e.g. "rev-income@3"
    connector_id      VARCHAR(60)  NOT NULL,       -- "rev-income", version-independent, for lookups
    version           INT NOT NULL,
    data_source_code  VARCHAR(60) NOT NULL REFERENCES catalog_data_source(code),
    data_category     VARCHAR(60) NOT NULL,
    capabilities      JSONB NOT NULL,              -- {"FETCH": {...}, "VERIFY": {...}} - HLD §7.1
    inputs            JSONB NOT NULL,
    sla_ms            INT,                         -- stays per-connector: two ops on one server can differ
    status            VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED','RETIRED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connector_id, version)
);

CREATE TABLE catalog_mapping (
    ref            VARCHAR(120) PRIMARY KEY,   -- e.g. "map-rev-income@3"
    connector_ref  VARCHAR(120) NOT NULL REFERENCES catalog_connector(ref),
    rules          JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_journey (
    code                 VARCHAR(60) PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    bpmn_ref             VARCHAR(120) NOT NULL,
    required_categories  TEXT[] NOT NULL,
    policy               JSONB NOT NULL DEFAULT '{}',   -- {"accept_stale": true, "sla_hours": 72}
    status               VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Every one of these is a real foreign key — all six tables belong to `catalog`, so full
referential integrity is free and correct here (contrast
[LLD.md §3.2](../LLD.md#32-cross-module-references-are-soft-not-foreign-keys), which
applies only *across* module boundaries).

## 2. Mapping validation at save time — the other half of the shared-constant fix

```java
package com.samanvay.catalog.internal.service;

@Service
class MappingService {

    MappingDefinition save(MappingDraft draft) {
        for (var rule : draft.rules())
            for (var transform : rule.transforms())
                if (!MappingTransforms.NAMES.contains(transform.fn()))
                    throw new UnknownTransformException(transform.fn());
        return mappings.insert(draft);
    }
}
```

`MappingTransforms.NAMES` is the `shared` constant from
[LLD.md §6](../LLD.md#6-shared--what-deliberately-lives-here-and-the-rule-against-growing-it) —
this is the validation half; `connector`'s `MappingExecutor` (§5 of its own LLD) is the
execution half, and a test on that side asserts the two never diverge.

## 3. Connector lifecycle — the state machine and its guard

```java
package com.samanvay.catalog.internal.service;

@Service
class ConnectorLifecycleService {

    ConnectorDefinition publish(String connectorRef, ConnectorTestReport testReport) {
        if (!testReport.passed())
            throw new ConnectorNotReadyException(connectorRef, testReport.failures());

        var connector = connectors.findByRef(connectorRef)
            .orElseThrow(() -> new ConnectorNotFoundException(connectorRef));
        if (connector.status() != ConnectorStatus.DRAFT)
            throw new IllegalConnectorStateException(connectorRef, "only DRAFT can be published");

        connectors.updateStatus(connectorRef, ConnectorStatus.PUBLISHED);
        publisher.publishEvent(new ConnectorPublished(connectorRef));
        return connectors.findByRef(connectorRef).orElseThrow();
    }

    ConnectorDefinition newVersion(String connectorId, ConnectorDraft draft) {
        // A PUBLISHED connector is never edited in place - this always
        // inserts a new row at version = max(existing) + 1, status DRAFT.
        int nextVersion = connectors.maxVersion(connectorId) + 1;
        return connectors.insertDraft(connectorId, nextVersion, draft);
    }
}
```

**Test** in §3 doubles as the wizard's "Test connection" button and a CI contract test
(per [hld/06-connector.md §7](../hld/06-connector.md#7-connector-lifecycle)) — it runs the
real `ConnectorRuntime.execute()` against a synthetic subject and asserts the output
validates against `output_schema`. `ConnectorLifecycleService` does not run the test
itself; it only accepts the resulting `ConnectorTestReport` and gates publication on it.

## 4. Host allowlisting — the SSRF control, concretely

```java
package com.samanvay.catalog.internal.service;

@Service
class DataSourceService {

    private static final List<String> BLOCKED_CIDR_PREFIXES =
        List.of("127.", "10.", "172.16.", "192.168.", "169.254.");   // link-local & private ranges

    DataSourceDefinition register(DataSourceDraft draft) {
        if (BLOCKED_CIDR_PREFIXES.stream().anyMatch(draft.baseHost()::startsWith))
            throw new IllegalHostException(draft.baseHost());
        // ... DNS-resolves and re-checks the resolved IP too, not just the
        // literal string, since a hostname can resolve to a private range
        // even when the string itself doesn't look like one (rebinding).
        return dataSources.insert(draft);
    }
}
```

Only `INTEGRATION_ADMIN` can call `register()` — enforced at the edge (Spring Security
method security), not re-checked here; this service trusts its caller's role the way every
internal service in the system does (the role check happened once, at the boundary).

## 5. Sequences

### 5.1 Onboarding wizard (Phase 1 — CRUD, not the importer)

```
INTEGRATION_ADMIN
    │  POST /api/catalog/departments
    ▼
CatalogController → DepartmentService.register(draft)
    │  POST /api/catalog/data-sources         (host allowlist check, §4)
    ▼
DataSourceService.register(draft)
    │  POST /api/catalog/connectors            (status = DRAFT)
    ▼
ConnectorDraftService.create(draft)
    │  POST /api/catalog/mappings              (transform-name check, §2)
    ▼
MappingService.save(draft)
    │  POST /api/catalog/connectors/{ref}/test
    ▼
   [ConnectorRuntime.execute() against a synthetic subject - connector module]
    │  POST /api/catalog/connectors/{ref}/publish
    ▼
ConnectorLifecycleService.publish(ref, testReport)   → status = PUBLISHED, ConnectorPublished event
```

## 6. Error handling

| Exception (`catalog.api`) | Raised when | HTTP |
|---|---|---|
| `UnknownTransformException` | A mapping references a transform outside `MappingTransforms.NAMES` | 400 |
| `IllegalHostException` | A data source's host is private/link-local, or resolves to one | 400 |
| `ConnectorNotReadyException` | `publish()` called without a passing test report | 409 |
| `IllegalConnectorStateException` | `publish()` on a non-`DRAFT` connector | 409 |
| `ConnectorNotFoundException` / `DepartmentNotFoundException` | Referencing an unknown ref/code | 404 |

## 7. Events

| Event | Payload |
|---|---|
| `ConnectorPublished` | `{connectorRef}` |
| `ConnectorDeprecated` | `{connectorRef}` |
| `DepartmentRegistered` | `{departmentCode}` |
| `SchemaPublished` | `{schemaRef}` |

## 8. Tests

| Test | Proves |
|---|---|
| `MappingServiceTest` | A mapping using an unknown transform is rejected at save, before it ever reaches `connector` |
| `DataSourceHostAllowlistTest` | `127.0.0.1`, `169.254.169.254` (the classic cloud-metadata SSRF target), and a hostname that resolves to `10.0.0.5` are all rejected |
| `ConnectorLifecycleServiceTest` | Publish without a passing test fails; publishing twice fails; `newVersion` never mutates the existing `PUBLISHED` row |
| `ConnectorCatalogIT extends PostgresIntegrationTest` | `resolve(department, category, capability)` returns the correct pinned version when two versions of the same connector exist |
