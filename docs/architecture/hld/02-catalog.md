# Module: `catalog`

| | |
|---|---|
| **Plane** | Control |
| **Owner** | D |
| **Phase** | 1 (core) → 2 (onboarding wizard) → 4 (specification import) |
| **Depends on** | `audit`, `SecretStore` |
| **Depended on by** | `identity`, `registry`, `consent`, `connector`, `orchestration`, `tracking` |
| **HLD context** | [§5.6](../HLD.md#56-catalog) · [§7.1](../HLD.md#71-a-connector-is-data) · [§8](../HLD.md#8-department-onboarding) |

---

## 1. Purpose

Hold the platform's configuration: which departments exist, how to reach their systems,
what data they expose, what shape that data takes canonically, and how to translate
between the two.

`catalog` is what makes the platform configurable rather than programmed. **Every "add a
department without writing Java" claim resolves to a row in this module.**

## 2. Responsibilities

### Owns

- Department registration and metadata
- Data source definitions: protocol, base URL, auth type, `auth_config_ref`
- Connector definitions and their versioned lifecycle
- Canonical schemas (JSON Schema documents)
- Field mappings
- Journey definitions: BPMN reference, required categories, policy such as `accept_stale`
- The onboarding wizard's backing API
- Specification import and mapping suggestion (Phase 4)

### Explicitly not responsible for

- **Executing connectors.** `catalog` describes; `connector` executes.
- **Storing credentials.** It stores a `auth_config_ref` pointing at `SecretStore`. Never
  a secret value, not even encrypted.
- **Deciding whether an access is allowed.** That is `consent` + `AccessAuthority`.
- **Running workflows.** It stores the BPMN reference; `orchestration` executes it.

## 3. Public interface — `com.samanvay.catalog.api`

```java
public interface DepartmentCatalog {
    Optional<Department> byCode(String code);
    List<Department> all();
}

public interface ConnectorCatalog {
    /** Resolves the connector serving a category for a department, at a pinned version. */
    Optional<ConnectorDefinition> resolve(String departmentCode,
                                          DataCategory category,
                                          Capability capability);
    ConnectorDefinition byRef(String connectorRef);      // "rev-income@3"
    List<ConnectorDefinition> published();
}

public interface SchemaCatalog {
    JsonSchema byRef(String schemaRef);                  // "Credential/IncomeCertificate@1"
    ValidationResult validate(String schemaRef, JsonNode document);
}

public interface MappingCatalog {
    MappingDefinition byRef(String mappingRef);
}

public interface JourneyCatalog {
    JourneyDefinition byCode(String journeyCode);
    JourneyPolicy policy(String journeyCode);            // accept_stale, SLA, required categories
}
```

`ConnectorDefinition` exposes `supports(Capability)` — orchestration never learns what a
Revenue connector is.

## 4. Data owned

| Table | Notes |
|---|---|
| `catalog_department` | code, name, IdP realm, contact, SLA defaults, status |
| `catalog_data_source` | department, protocol, base URL/host, auth type, `auth_config_ref`, health |
| `catalog_connector` | data source, category, `capabilities` jsonb, inputs, response config, resilience config, version, status |
| `catalog_schema` | name, version, JSON Schema document |
| `catalog_mapping` | connector, direction, rules jsonb |
| `catalog_journey` | code, BPMN ref, required categories, policy jsonb |

All tables prefixed `catalog_` in the shared schema. **Protocol and base URL live only on
`catalog_data_source`** — a connector never carries its own copy, so there is one place to
change and one source of truth.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `ConnectorPublished` | `connector` (cache invalidation), `notifications` |
| `ConnectorDeprecated` | `orchestration` (warn on pinned journeys) |
| `DepartmentRegistered` | `registry` (category bootstrapping) |
| `SchemaPublished` | `connector` (validator cache) |

### Consumed
None.

## 6. Key decisions

### A connector is data, not code

The whole extensibility claim rests here. A department on an existing protocol costs a row;
new Java is written only for a genuinely new communication protocol.

### Connectors and mappings are versioned; live ones are never edited

```
DRAFT ──test──► PUBLISHED@v1 ──► PUBLISHED@v2 ──► DEPRECATED ──► RETIRED
                     ▲                                 │
              journeys pin a major version ─────────────┘
```

Editing a published connector changes behaviour for in-flight applications with no record
of what the behaviour was when they started. Versioning makes the audit trail meaningful:
`connector: rev-income@3` in a provenance record must always mean the same thing.

### Input binding is a fixed context, not an expression language

`inputs[].from` addresses only `link.*`, `profile.*`, `grant.*`, `journey.var.*`. A
connector cannot reach data outside the scope of the current grant, because there is
nothing else addressable.

### The mapping DSL cannot loop, call out, or execute

Fixed transform registry: `trim`, `upper`, `lower`, `date_parse`, `coalesce`, `split_name`,
`lookup`, `mask`. An unsupported transformation is added as one named function — a small
reviewable change — never by embedding a scripting engine that then needs a sandbox.

### Base URLs are set only on data sources, by `INTEGRATION_ADMIN`, host-allowlisted

A connector *is* an administrator-supplied URL that the server fetches — SSRF as a feature.
Connector definitions supply paths; hosts come from the data source, which is allowlisted
and blocks link-local and private ranges. See [§9.3](../HLD.md#93-threat-model).

### Specification import is Phase 4 and cuttable

The wizard alone demonstrates onboarding. The importer makes it compelling, and is
deliberately not a foundational dependency.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Connector references a missing schema | Publish rejected at validation, not at runtime |
| Journey pins a retired connector version | Journey publish rejected; existing instances continue on the pinned version |
| `auth_config_ref` resolves to nothing | Connector test fails with a clear message; publish blocked |
| Mapping references an unknown transform | Rejected at mapping save |
| Uploaded specification is malformed | Rejected with parse errors surfaced; no partial import |

## 8. Acceptance criteria

- [ ] A department, data source, connector and mapping can be created entirely through the API
- [ ] Publishing a connector requires a successful test run
- [ ] A published connector cannot be edited; only a new version can be created
- [ ] Credentials are write-only: settable via API, never returned by any endpoint
- [ ] A data source pointing at `169.254.169.254` or a private range is **rejected**
- [ ] A mapping referencing `eval` or an unknown function is rejected
- [ ] Journey 3's `LandParcel` schema and connectors are created with **zero Java changes**
- [ ] Specification import produces suggestions that a human must approve before use

## 9. Open questions for LLD

- Schema versioning compatibility rules: are additive changes within a major version
  automatically compatible, and is that enforced at publish time?
- Whether `catalog_connector.capabilities` jsonb should be normalised into a child table
  once more than three capabilities exist.
- Mapping suggestion confidence thresholds, and whether below-threshold suggestions are
  shown at all or hidden to reduce reviewer fatigue.
