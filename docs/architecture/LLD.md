# Samanvay — Low Level Design (Combined)

| | |
|---|---|
| **Document** | Low Level Design (LLD) — cross-cutting conventions |
| **Status** | Approved — implementation may begin |
| **Depends on** | [HLD.md](HLD.md) (architecture, principles, data model, flows) and [hld/](hld/README.md) (per-module charters) |
| **Companion** | [lld/](lld/README.md) — per-module low-level design (entities, DDL, class design, sequence diagrams, tests) |

---

## 1. Purpose and scope

This document holds decisions that apply **across** modules — naming conventions, how
migrations coexist in one shared schema, how errors are shaped at the API boundary, how
tests are organized, and the sequence diagrams for flows that cross module boundaries.

Anything specific to one module — its entities, its own DDL, its internal class design,
its own sequence diagrams, its test list — lives in that module's document under
[`lld/`](lld/README.md). A module LLD references this document rather than repeating its
conventions.

**If this document and the HLD disagree on architecture, the HLD wins.** This document
only makes the HLD's decisions concrete enough to type.

## 2. Package layout

```
com.samanvay
├── SamanvayApplication.java
│
├── audit
│   ├── api                    ← AuditService, AuditEntry, VerificationResult, ...
│   └── internal
│       ├── domain             ← JPA entities
│       ├── repository         ← Spring Data repositories
│       ├── service            ← business logic implementing the api interfaces
│       └── web                ← controllers, only if the module exposes REST directly
│
├── catalog   { api, internal.{domain,repository,service,web} }
├── identity  { api, internal.{domain,repository,service,web} }
├── registry  { api, internal.{domain,repository,service,web} }
├── consent   { api, internal.{domain,repository,service,web} }
├── connector { api, internal.{domain,repository,service,protocol,mapping,web} }
├── orchestration { api, internal.{domain,repository,service,workflow,web} }
├── tracking  { api, internal.{domain,repository,service,web} }
├── notifications { api, internal.{domain,repository,service,channel,web} }
│
└── shared                     ← see §6. Small and deliberately kept that way.
```

**The rule Spring Modulith enforces:** only `<module>.api` is visible to other modules.
`<module>.internal.*` is invisible outside its own module — `ApplicationModules.verify()`
fails the build the moment another module imports an `internal` class.

Two modules have an extra internal package because their responsibility genuinely has an
extra dimension: `connector` has `protocol` (the four `ProtocolAdapter` implementations)
and `mapping` (the DSL executor); `orchestration` has `workflow` (the Flowable adapter
behind `WorkflowEngine`); `notifications` has `channel` (the three `NotificationChannel`
implementations). No other module gets an extra package — if one seems to need it later,
that is itself a signal worth discussing before adding it.

## 3. Database conventions

### 3.1 One schema, one exception

Every module's tables live in the default `public` schema, prefixed with the module name
(`identity_citizen`, `consent_artifact`, `catalog_department`, ...). **`audit` is the one
exception** — it has its own PostgreSQL schema (`audit`) and its own database role, because
its integrity claim depends on the application role having no `UPDATE`/`DELETE` grant on
`audit_entry`, which is unmanageable to express per-table inside a shared schema (see
[hld/01-audit.md §6](hld/01-audit.md#6-key-decisions)).

This was a deliberate trade against nine physical schemas: Spring Modulith's
`ApplicationModules.verify()` checks **Java package** boundaries at build time, not SQL —
it would not notice a repository in one module joining across another module's tables.
The boundary is therefore enforced twice, by two different mechanisms that fail closed
independently:

1. **Compile time** — `ApplicationModules.verify()`, run in every CI build.
2. **Query time** — an ArchUnit rule (§3.4) asserting that a module's `@Repository`
   interfaces and native queries only reference tables carrying that module's own prefix.

### 3.2 Cross-module references are soft, not foreign keys

Within a module, use real foreign keys — that module owns all the tables involved, and
referential integrity is free and correct to enforce.

**Across modules, do not add a SQL foreign key.** For example,
`consent_artifact.subject_citizen_id` is a plain `UUID NOT NULL` column with no
`REFERENCES identity_citizen(id)`. Reasoning:

- A physical FK between `consent`'s table and `identity`'s table is exactly the kind of
  coupling the module boundary exists to prevent. It would survive even if the Java-level
  dependency were later removed, and it blocks the extraction path P6 keeps open (any
  module becoming a separate service without touching its callers) — you cannot have a
  cross-service foreign key.
- The cost is real and is stated plainly: referential integrity for a cross-module
  reference is an **application-level invariant** (validated by calling the owning
  module's public interface, e.g. `IdentityLinking.exists(citizenId)`), not a
  database-enforced one. A bug could in principle write an orphaned reference. This is
  covered by tests, not by a constraint — the same trade every service-oriented system
  with independently-owned data makes.

### 3.3 Column and type conventions

| Concern | Convention | Why |
|---|---|---|
| Primary key | `UUID`, generated in application code (`UUID.randomUUID()` or a v7 generator — see §3.5), column name `id` | UUIDs merge cleanly across modules with no coordination, and don't leak row counts. `audit_entry` is the deliberate exception (`seq BIGSERIAL`) — hash chaining requires a strict, gap-detectable order, which a UUID cannot give you |
| Timestamps | `TIMESTAMPTZ`, never bare `TIMESTAMP` | The whole system runs in UTC (`TimeZone.setDefault(UTC)` in `main()`, see the [bootstrap commit](../../../README.md)). `TIMESTAMPTZ` stores the instant, not a wall-clock string, so it's correct regardless of which timezone a future reader's session is in |
| Enums | `VARCHAR(n) NOT NULL CHECK (col IN (...))`, not a native Postgres `ENUM` type | Postgres `ENUM` alteration is awkward across versions and migrations; a `CHECK` constraint is a plain `ALTER TABLE` |
| Flexible/structured data | `JSONB` | Used where the HLD already calls for schema-defined-at-runtime content: `catalog_schema.definition`, `catalog_connector.capabilities`, `catalog_mapping.rules`, `identity_candidate_match.features`, `audit_entry.meta` |
| Money / quantities | Never `FLOAT`/`DOUBLE` | Not yet needed anywhere in Phase 0–1, stated here so nobody reaches for one later. Use `NUMERIC` if a journey introduces a monetary field |
| Booleans | `BOOLEAN`, never `CHAR(1)`/`SMALLINT` | No reason to be clever |

### 3.4 The prefix-boundary ArchUnit rule

```java
// src/test/java/com/samanvay/ArchitectureTest.java
@AnalyzeClasses(packages = "com.samanvay")
class ArchitectureTest {

    @ArchTest
    static final ArchRule modules_only_touch_their_own_tables =
        classes().that().resideInAPackage("..internal.repository..")
            .should(new TablePrefixMatchesModuleCondition());

    @ArchTest
    static final ArchRule modulith_is_respected =
        (ArchRule) (root) -> ApplicationModules.of(SamanvayApplication.class).verify();
}
```

`TablePrefixMatchesModuleCondition` inspects each `@Repository`/`@Entity`'s declared
`@Table(name = ...)` and asserts it starts with the owning module's prefix. This is a
Phase 0 deliverable (see [lld/01-audit.md](lld/01-audit.md)) — it is written and passing
before any module beyond `audit` adds a table.

### 3.5 Migration coordination — the actual day-two problem

Nine modules, six people, one `src/main/resources/db/migration/` folder, all writing
`Vn__description.sql` files. Two people picking `V7` on the same day is a certainty, not
an edge case, and it fails at merge time in the worst way — silently, if Git doesn't
conflict on the filenames themselves (`V7__add_x.sql` and `V7__add_y.sql` are different
files; Flyway does not care that both claim version 7 until it tries to apply them against
a database that already ran one of them, and even then it can produce a confusing
"validate failed" rather than an obvious merge conflict).

**Convention:** each module owns a reserved hundred-block:

| Module | Reserved range |
|---|---|
| `audit` | V1–V19 |
| `catalog` | V20–V39 |
| `identity` | V40–V59 |
| `registry` | V60–V79 |
| `consent` | V80–V99 |
| `connector` | V100–V119 |
| `orchestration` | V120–V139 |
| `tracking` | V140–V159 |
| `notifications` | V160–V179 |

Twenty migrations per module is generous for a hackathon build; if a module genuinely
exceeds it, that is a five-minute conversation, not a blocker. This is deliberately a
convention enforced by the PR template (see the repo's
[pull request template](../../.github/pull_request_template.md)), not by tooling — a
build-time check here would be more machinery than the problem deserves.

### 3.6 Flyway placement

Every module's migrations live in the same physical folder,
`src/main/resources/db/migration/`, prefixed by module for readability
(`V1__audit_init.sql`, `V40__identity_init.sql`, ...). One folder, one Flyway history
table (`flyway_schema_history`), one linear history — this is a deliberate consequence of
§3.1 (one shared schema): splitting migration folders per module would suggest a
separation the schema itself doesn't have.

## 4. Error handling

### 4.1 Exception hierarchy

```java
package com.samanvay.shared;

public abstract class SamanvayException extends RuntimeException {
    protected SamanvayException(String message) { super(message); }
    protected SamanvayException(String message, Throwable cause) { super(message, cause); }
}
```

Each module defines its own subtypes in its `api` package (not `shared`) — e.g.
`consent.api.AccessDenied`, `identity.api.LinkNotFound`. `shared` holds only the common
root; it never accumulates module-specific exception types (see §6 on why `shared` stays
small).

### 4.2 Mapping to HTTP

One `@RestControllerAdvice` in `shared`, using Spring's `ProblemDetail` (RFC 7807) so every
error response has the same shape regardless of which module raised it:

```json
{
  "type": "https://samanvay.dev/problems/consent/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "No active consent for category INCOME_CERTIFICATE",
  "instance": "/api/applications/MH-SCH-2026-000123",
  "reason": "NO_CONSENT"
}
```

The `reason` extension field carries the module's own enum (e.g. `AccessAuthority`'s
`DenialReason` from [hld/05-consent.md](hld/05-consent.md#3-public-interface--comsamanvayconsentapi)) —
countable on a dashboard, unlike parsing `detail` text.

### 4.3 What is never caught and swallowed

An audit write failure, a signature verification failure, and a Modulith event
publication failure are never caught into a "best effort, log and continue" path. See each
module's own LLD for the specific rule (`audit`, `consent`).

## 5. Testing conventions

| Layer | Tool | Runs |
|---|---|---|
| Unit | JUnit 5 + AssertJ, no Spring context | Every build, seconds |
| Module integration | `@ApplicationModuleTest` (Spring Modulith's own test slice — boots only the target module plus its declared dependencies) | Every build |
| Repository / schema | Testcontainers Postgres, real Flyway migrations applied | Every build (CI has Docker; see [ci.yml](../../.github/workflows/ci.yml)) |
| Connector contract | WireMock golden fixtures, no live mock department needed | Every build |
| End to end | docker-compose, one per journey | Before a phase is marked done, not on every commit |
| Architecture | ArchUnit + `ApplicationModules.verify()` | Every build |

**One shared Testcontainers base class**, not one container per test class:

```java
package com.samanvay.shared.test;

@Testcontainers
public abstract class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withReuse(true);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

`withReuse(true)` plus a `~/.testcontainers.properties` with `testcontainers.reuse.enable=true`
on each developer's machine keeps local test runs fast — one Postgres container across a
whole test session, not one per class. CI does not enable reuse (each run is a fresh
runner anyway).

## 6. `shared` — what deliberately lives here, and the rule against growing it

Six things — the sixth is included precisely because it was made the trigger for a pause
and a real check, not a reflex; see the note after the list:

1. `SamanvayException` (§4.1)
2. The `ProblemDetail` advice (§4.2)
3. `PostgresIntegrationTest` (§5)
4. The three cross-cutting fixed-context types every connector binding expression can
   address — `LinkRef`, `ProfileRef`, `GrantRef` (referenced from
   [hld/06-connector.md §7.1](hld/06-connector.md))
5. `CanonicalJson` — deterministic JSON serialization (sorted map keys, ISO-8601 instants,
   no epoch-millis ambiguity). `audit`'s hash chain and `consent`'s `AccessGrant` signing
   both sign or hash a byte representation of a structured object; both need that
   representation to be byte-identical for the same logical content, independent of JVM,
   map insertion order, or restart. That is the same requirement, not two similar ones —
   see [lld/01-audit.md §4](lld/01-audit.md#4-the-hash-chain--the-actual-algorithm) for
   where it was first needed and [lld/05-consent.md](05-consent.md) for its second,
   confirming use.
6. `MappingTransforms.NAMES` — the fixed set of valid mapping-DSL function names
   (`trim`, `upper`, ...). `catalog` validates a mapping definition against this set at
   save time; `connector` derives its actual executable registry's keys from the same
   set. If these two lists were maintained separately, they would eventually drift —
   either `catalog` accepting a mapping `connector` can't run, or rejecting one it could.
   This is the entire mapping DSL's closed-security-boundary claim
   ([HLD §5.2](HLD.md#52-mapping-dsl--deliberately-not-a-programming-language)), so
   keeping the two in sync isn't cosmetic. See
   [lld/06-connector.md §5](lld/06-connector.md#5-mapping-dsl-executor--the-fixed-registry-literally)
   and [lld/02-catalog.md](02-catalog.md).

**The rule:** a type goes in `shared` only if at least two modules need the *identical*
thing, not a similar one — checked against that test each time, as just done for #6, not
assumed. The next candidate is the signal to run the same check again, not to add it on
reflex. `shared` growing without bound is how module boundaries quietly dissolve; a
`shared` that never grows past its first entries because every candidate was actually
checked is the sign the rule is working.

## 7. Cross-module sequence diagrams

These four flows were introduced at the architecture level in
[HLD.md §6](HLD.md#6-core-flows). Here they're shown as the actual interface calls between
modules' public APIs, so the wiring is unambiguous before anyone writes the calling code.
Each module's own LLD covers what happens *inside* its own box; this section covers the
arrows *between* boxes.

### 7.1 Consent-gated fetch (HLD §6.1)

```
OrchestrationStepDelegate                 (orchestration.internal.workflow)
        │
        │ authorize(AccessRequest)
        ▼
AccessAuthority                           (consent.api)
        │
        ├─▶ IdentityLinking.activeLink(citizenId, deptCode)      (identity.api)
        ├─▶ ConsentService.find(requester, subject, category, purpose)  (consent.internal)
        ├─▶ DiscoveryRegistry.locate(subject, deptCode, category, requester)  (registry.api)
        │
        │  all satisfied
        ▼
   sign AccessGrant  (private key from SecretStore)
        │
        │ returns AccessDecision.Granted(grant)
        ▼
OrchestrationStepDelegate
        │
        │ execute(grant, FETCH, inputs)
        ▼
ConnectorRuntime                          (connector.api)
        │
        │ verifyOrThrow(grant, ...)   — public key only, cannot mint
        ▼
   [protocol adapter → department → normalize → DQ → mapping → provenance]
        │
        ▼
   ConnectorResult.Success(canonical, provenance)
        │
        ▼
OrchestrationStepDelegate  sets workflow variable, step → COMPLETED
```

**The one rule this diagram exists to make impossible to violate:** there is no arrow from
`OrchestrationStepDelegate` directly to `ConnectorRuntime` without first passing through
`AccessAuthority`. `ConnectorRuntime.execute(...)` has no overload that omits the grant —
see [hld/06-connector.md §6](hld/06-connector.md#6-key-decisions).

### 7.2 Parallel fan-out and degraded mode (HLD §6.2)

```
JourneyService.start(journeyCode, citizenId, submission)   (orchestration.api)
        │
        ▼
WorkflowEngine.start(processKey, variables)                (orchestration.api port)
        │
        ▼
   [Flowable parallel gateway — one branch per required category]
        │
   ┌────┼────────────────┬─────────────────┐
   ▼    ▼                ▼                 ▼
 Flow 7.1  Flow 7.1     Flow 7.1        Flow 7.1
 (income)  (caste)      (marks)         (bank)  ─── ConnectorResult.Unavailable(TIMEOUT)
   │         │             │                 │
   ▼         ▼             ▼                 ▼
COMPLETED COMPLETED     COMPLETED        step → PENDING_SOURCE
                                              │
                                    WorkflowEngine.scheduleRetry(instanceId, activityId, delay)
                                              │
                              ApplicationTracking receives StepPendingSource event
                              (tracking.internal — event listener, not a direct call)
                                              │
                              application aggregate status → PARTIALLY_VERIFIED
                                              (never FAILED while independent steps can proceed)
```

### 7.3 Mid-flight revocation (HLD §6.3)

```
ConsentService.revoke(consentId, citizenId, reason)        (consent.api)
        │
        │ consent_artifact.version++, status = REVOKED
        ▼
   publish ConsentRevoked                (Spring ApplicationEventPublisher)
        │
        │ Modulith's Event Publication Registry persists this in the SAME
        │ transaction as the revoke — see hld/01-audit.md's reasoning for
        │ why audit does the same thing for the same reason.
        ▼
   ┌────────────────────────────┬─────────────────────────────┐
   ▼                            ▼                              ▼
orchestration                notifications                  registry
@ApplicationModuleListener   @ApplicationModuleListener    @ApplicationModuleListener
  │                            │                              │
  ▼                            ▼                              ▼
find in-flight steps        notify citizen               sensitive pointers tied
holding a grant for            + officer                 to this consent become
this consent_id →                                        undiscoverable again
mark AUTHORIZATION_WITHDRAWN
```

Separately, and not shown as an arrow because it is not a call at all: any
`AccessGrant` already issued for this consent fails `verifyOrThrow` the instant
`consent_access_grant` (see §3.1 naming) is checked against the new `consent_version` —
this is a field comparison inside `connector`, not a notification anyone has to act on.
That's the actual mechanism behind the "within 60 seconds" claim in HLD §6.3, independent
of the event-driven cleanup shown above.

### 7.4 Batch ingestion (HLD §6.4)

```
BatchIngestor.ingest(dataSourceCode)                        (connector.api, on a schedule)
        │
        ▼
   [SFTP poll → checksum → per-row DQ]
        │
        │ publish BatchIngestCompleted { dataSourceCode, rows[] }
        ▼
registry.internal  @ApplicationModuleListener
        │
        ▼
DiscoveryRegistry.upsert(...)  for each row, as_of = file timestamp
        │
        ▼
   later, at request time:
   AccessAuthority → DiscoveryRegistry.locate(...) returns Pointer{freshness: STALE(14h)}
        │
        ▼
   JourneyCatalog.policy(journeyCode).acceptStale()   (catalog.api)
        │
   ┌────┴────┐
   ▼         ▼
 true      false → step routes to manual verification, not to ConnectorRuntime at all
```

---

**Read next:** [lld/README.md](lld/README.md) for the module index, or jump straight to
[lld/01-audit.md](lld/01-audit.md) — the first module built, and the one every other
module's tests ultimately depend on for a verifiable audit trail.
