# Module: `connector`

| | |
|---|---|
| **Plane** | Data |
| **Owner** | C (runtime, REST + SOAP) · D (SFTP + JDBC, batch) |
| **Phase** | 1 (REST, SOAP) → 2 (SFTP, JDBC, batch) |
| **Depends on** | `catalog`, `consent` (`AccessGrantVerifier`), `audit`, `SecretStore` |
| **Depended on by** | `orchestration` |
| **HLD context** | [§7](../HLD.md#7-connector-subsystem) |

---

## 1. Purpose

Execute a connector definition against a department system and return validated canonical
data — or fail in a way the orchestrator can reason about.

This is where the platform's extensibility claim is cashed. **A connector describes *what*
government resource is accessed; a protocol adapter describes *how* to communicate.** That
separation is what makes a new department on an existing protocol cost configuration only.

## 2. Responsibilities

### Owns

- The `ProtocolAdapter` SPI and its four implementations
- The execution pipeline: verify → bind → render → transport → normalize → validate → map → provenance
- Mapping DSL execution and the transform function registry
- Resilience: timeout, retry, circuit breaker, bulkhead
- Batch ingestion from file-based departments
- `submission_attempt` idempotency for `SUBMIT`

### Explicitly not responsible for

- **Deciding whether an access is allowed.** It *verifies* a grant; it never issues one.
- **Storing what it fetched.** Results are returned in memory and never persisted.
- **Knowing about journeys.** It has no idea what a scholarship is.
- **Defining connectors.** `catalog` owns definitions; this module executes them.

## 3. Public interface — `com.samanvay.connector.api`

```java
public interface ConnectorRuntime {

    /** The ONLY entry point. AccessGrant is a required argument by construction. */
    ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs);
}

public sealed interface ConnectorResult {
    record Success(JsonNode canonical, Provenance provenance)      implements ConnectorResult {}
    record NotFound(String detail)                                 implements ConnectorResult {}
    record Unavailable(FailureKind kind, boolean retryable)        implements ConnectorResult {}
    record Invalid(List<DataQualityViolation> violations)          implements ConnectorResult {}
}

/** Implemented per protocol. The one SPI with four real implementations. */
public interface ProtocolAdapter {
    String protocol();                              // REST | SOAP | SFTP_CSV | JDBC
    AdapterResponse execute(AdapterRequest request);
}

public interface BatchIngestor {
    BatchResult ingest(String dataSourceCode);      // scheduled; publishes BatchIngestCompleted
}
```

`ConnectorResult` is a sealed hierarchy, not an exception, because **`Unavailable` is a
normal outcome** that `orchestration` handles by entering degraded mode
([§6.2](../HLD.md#62-journey-execution--parallel-fan-out-and-degraded-mode)) — not an
error condition.

## 4. Data owned

| Table | Notes |
|---|---|
| `connector_submission_attempt` | `id` (idempotency key), `grant_id`, `workflow_instance_id`, `connector_id`, `request_hash`, `status`, `external_reference` |
| `connector_batch_file` | data source, filename, checksum, `as_of`, rows processed, row offset, status |
| `connector_exception` | Failed batch rows and unresolved fetch failures awaiting officer action |

**No table holds fetched payloads.** `connector_exception` holds the failing row's raw text
for diagnosis — which is an intentional, bounded exception, retained only until resolved.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `DataAccessed` | Observability. (Audit is written synchronously, not via event) |
| `ConnectorFailed` | `orchestration`, observability |
| `CircuitBreakerOpened` / `Closed` | Connector health dashboard |
| `BatchIngestCompleted` | `registry` (bulk pointer upsert) |
| `BatchRowRejected` | Exception queue, `notifications` |

### Consumed

| Event | Reaction |
|---|---|
| `ConnectorPublished` (from `catalog`) | Invalidate definition cache |
| `SchemaPublished` (from `catalog`) | Invalidate validator cache |

## 6. Key decisions

### The grant is a required argument

There is no `execute(...)` overload without an `AccessGrant`. Bypassing consent requires
forging a signature, not forgetting a check. This module holds only the **public**
verification key.

### Resilience is keyed on `data_source`, not on connector

The protected resource is *the department's server*. Six connectors pointing at Revenue
share one circuit breaker and one bulkhead — otherwise Revenue being slow exhausts the
thread pool six times over and takes Education's requests down with it.

This is the single most consequential detail in the module and the easiest to get wrong.

### `SUBMIT` idempotency is its own concept

A retried `FETCH` is free. A retried `SUBMIT` files the same scholarship twice.

Authorization and operation have different lifecycles, so `submission_attempt` is a
separate entity keyed independently of the grant. For the MVP the key is *derived* from the
grant, but nothing in the model assumes *one grant = one submission* permanently.

A retry after a timeout resolves to the original `external_reference` rather than creating
a duplicate. **This is the most likely real bug in the system and costs one table to
prevent.**

### Protocol-aware escaping is a security control, not formatting

| Adapter | The detail that is not optional |
|---|---|
| `RestAdapter` | URL-encode bound parameters; never concatenate into a path |
| `SoapAdapter` | XML-escape **every** substitution. DTDs and external entities disabled — a department response is untrusted XML |
| `SftpCsvAdapter` | Host-key verification on; checksum files to detect re-delivery |
| `JdbcAdapter` | Bound parameters only, `SELECT`-only guard, row-count cap, read-only credential at the department |

The `JdbcAdapter` is what a security reviewer will scrutinise hardest, so it is
deliberately the most constrained.

### Department responses are untrusted input

Size-capped, schema-validated and data-quality checked before anything downstream sees
them. A department returning 400 MB or a malformed envelope must not affect platform
availability.

### HTTP 200 with a fault body is the norm, not the exception

Legacy government systems routinely return success codes with error payloads.
`response.error_paths` in the connector definition exists for exactly this, and treating
transport success as business success is a bug this design anticipates.

### Batch is a plain chunked job

```java
// ponytail: single-node chunked job with offset restart.
// Move to Spring Batch if files exceed ~1M rows or need partitioned parallel steps.
```

A few thousand rows nightly does not justify a framework and ten extra tables.

## 7. Failure modes

| Failure | Result | Retryable |
|---|---|---|
| Department timeout | `Unavailable(TIMEOUT)` | Yes |
| Circuit breaker open | `Unavailable(BREAKER_OPEN)` | Yes, after the open window |
| HTTP 200 with fault body | `Unavailable(REMOTE_FAULT)` or `NotFound` per `error_paths` | Depends on fault |
| Record genuinely absent | `NotFound` | **No** |
| Response fails schema validation | `Invalid(violations)` → exception queue | No — configuration defect |
| Mapping references a missing source path | `Invalid` — surfaced as a connector defect, not a department outage | No |
| Oversized response | `Unavailable(RESPONSE_TOO_LARGE)`, connection aborted | No |
| Grant invalid, expired or reused | Exception — never a soft failure. Audited as a security event | No |
| Batch file re-delivered | Checksum match → skipped, logged, not reprocessed | — |
| Batch job dies mid-file | Resumes from recorded row offset | Yes |

The distinction between `NotFound` and `Unavailable` matters: the first means "this citizen
has no income certificate", the second means "we could not ask". Conflating them either
denies a legitimate applicant or retries forever.

## 8. Acceptance criteria

- [ ] No code path exists that fetches without a verified `AccessGrant`
- [ ] Six connectors on one data source share one circuit breaker and one bulkhead
- [ ] A `SUBMIT` retried after timeout returns the original `external_reference`, and the department receives one submission
- [ ] A SOAP response containing an external entity does **not** cause a file read (XXE test)
- [ ] A name containing `&`, `<` and `"` round-trips through the SOAP adapter correctly
- [ ] The JDBC adapter rejects any statement that is not a parameterized `SELECT`
- [ ] A 500 MB response is aborted without affecting other requests
- [ ] Golden-fixture contract tests pass in CI with no mock departments running
- [ ] Batch ingestion is restartable mid-file and idempotent on re-delivery
- [ ] Adding a REST department requires **zero** new Java

## 9. Open questions for LLD

- Whether `ExecutionInputs` should be resolved by `connector` or handed in fully resolved
  by `orchestration`. Resolving inside keeps the binding context authoritative in one
  place; resolving outside keeps this module simpler.
- XML→JSON normalization rules: attribute handling, repeated elements becoming arrays,
  namespace stripping. Needs to be specified, not discovered per connector.
- Retry budget interaction between Resilience4j retries and Flowable timer retries — two
  retry layers can multiply. One must be authoritative.
- Whether `connector_exception` raw-row retention needs a hard TTL independent of
  resolution.
