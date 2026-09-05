# Module: `registry`

| | |
|---|---|
| **Plane** | Control |
| **Owner** | B |
| **Phase** | 1 (pointers, discovery) → 2 (batch freshness, sensitivity policy) |
| **Depends on** | `catalog`, `audit` |
| **Depended on by** | `consent` / `AccessAuthority`, `orchestration` |
| **HLD context** | [§5.4](../HLD.md#54-registry--pointers-with-metadata-leakage-closed) · [§6.4](../HLD.md#64-batch-departments--freshness-as-data) |

---

## 1. Purpose

The **Identity & Data Discovery Registry**: know *what data exists, where, how fresh it is,
and how sensitive it is* — without holding the data itself.

This is the module that makes "federated by default, indexed centrally" work. It gives the
platform enough to route, check eligibility, populate dashboards and reason about
availability, while the payload stays at the source.

## 2. Responsibilities

### Owns

- Discovery pointers: subject, department, category, validity, freshness, sensitivity
- Discovery queries and their authorization
- Freshness tracking, including staleness for batch-sourced pointers
- Sensitivity classification and discovery policy per category

### Explicitly not responsible for

- **Holding payloads.** A pointer holds a `source_ref` locator, never data.
- **Fetching.** `connector` fetches; `registry` records that something exists and how fresh
  the knowledge is.
- **Deciding access.** It contributes the sensitivity and freshness inputs;
  `AccessAuthority` makes the decision.
- **Identity.** It records pointers *for* a subject; `identity` determines who that subject
  is.

## 3. Public interface — `com.samanvay.registry.api`

```java
public interface DiscoveryRegistry {

    /** Authorized discovery. Filters by requester clearance and discovery policy. */
    List<Pointer> discover(SubjectRef subject, RequesterRef requester,
                           Set<DataCategory> categories);

    /** Single-pointer lookup used by AccessAuthority. Also authorized, also audited. */
    Optional<Pointer> locate(SubjectRef subject, String departmentCode, DataCategory category,
                             RequesterRef requester);

    void upsert(PointerUpsert p);          // realtime observation or batch ingestion
    void withdraw(UUID pointerId, String reason);
}

public record Pointer(
    UUID id, SubjectRef subject, String departmentCode, DataCategory category,
    Sensitivity sensitivity,               // PUBLIC | RESTRICTED | SENSITIVE
    DiscoveryPolicy discoveryPolicy,       // VISIBLE | CONSENT_REQUIRED_TO_DISCOVER
    JsonNode sourceRef,                    // opaque locator, e.g. {"endpoint":…,"key":…}
    LocalDate issuedAt, LocalDate validUntil,
    Instant asOf, FreshnessMode freshnessMode,   // REALTIME | BATCH
    PointerStatus status
) {}
```

## 4. Data owned

| Table | Notes |
|---|---|
| `registry_pointer` | One row per (subject, department, category). Locator only, never payload |
| `registry_category_policy` | Per-category default sensitivity and discovery policy |
| `registry_clearance` | Which requester is cleared for which sensitivity class |

`subject_type` ∈ `PERSON | ORGANISATION | LAND_PARCEL` — the last one arrives in Journey 3
as data, not code.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `PointerUpserted` | `notifications` (e.g. "your income certificate has been renewed") |
| `PointerExpired` | `orchestration` (in-flight journeys depending on it) |
| `StalenessThresholdBreached` | Observability dashboards |

### Consumed

| Event | Reaction |
|---|---|
| `LinkAsserted` | Discover pointers at the newly linked department |
| `BatchIngestCompleted` (from `connector`) | Bulk pointer upsert with `as_of` = file timestamp |
| `DepartmentRegistered` | Seed category policy defaults |

## 6. Key decisions

### Metadata is not neutral — the leak is closed explicitly

A pointer reading `Citizen X → Social Justice → Caste Certificate` **leaks caste** without
exposing the certificate. The same holds for disability, health and legal-proceeding
categories. *"We store only metadata"* is not, by itself, a privacy guarantee.

Three mitigations, all cheap:

1. **Discovery is authorized.** A department sees only pointers in categories it is cleared
   for. Scholarship sees income and caste; transport sees neither.
2. **Discovery reads are audited**, exactly as data fetches are. "Who looked up what
   existed" is a genuine access event.
3. `SENSITIVE` categories default to `CONSENT_REQUIRED_TO_DISCOVER` — the pointer is
   invisible until the citizen authorizes that requester to learn it exists.

### Freshness is data, not an assumption

Batch-sourced pointers carry `as_of` and are reported as `STALE(14h)`, not silently served
as current. The officer sees *"property record as of 02:00 today."*

Whether stale is acceptable is **journey policy** (`accept_stale`), owned by `catalog` —
so Journey 3 can hold a different tolerance from Journey 1 with no code change.

### `source_ref` is opaque to everyone except its connector

```jsonc
{ "endpoint": "getIncomeCert", "key": "RC-4471-88" }
```

The registry does not interpret it. This keeps the registry independent of protocol detail
and prevents a locator from quietly becoming a cache.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Pointer exists but the department no longer has the record | Fetch returns not-found; pointer marked `WITHDRAWN`; audited |
| Batch file never arrives | `as_of` ages; staleness breach alarm; journeys with `accept_stale: false` route to manual verification |
| Sensitivity misclassified as `PUBLIC` | Treated as a security incident. Category defaults are admin-only and audited |
| Discovery by an uncleared requester | Empty result — **not** an error. An error would itself confirm existence |
| Duplicate pointers for one subject/category | Unique constraint; upsert is idempotent on (subject, department, category) |

Note the fourth row: returning "forbidden" instead of "empty" leaks the existence of the
very record the policy is protecting.

## 8. Acceptance criteria

- [ ] No `registry_*` table contains any payload value from a department
- [ ] Discovery by an uncleared requester returns empty, not an error
- [ ] Discovery reads produce audit entries
- [ ] A `SENSITIVE` + `CONSENT_REQUIRED_TO_DISCOVER` pointer is invisible before consent and visible after
- [ ] Batch ingestion sets `as_of` from the file timestamp, not from ingestion time
- [ ] A stale pointer is reported with its age, and journey policy governs acceptance
- [ ] `LandParcel` pointers work with zero code change (Journey 3)

## 9. Open questions for LLD

- Staleness thresholds: per category, per department, or per journey — and who sets them.
- Whether pointer discovery for a newly linked department is synchronous (blocking the
  linking flow) or asynchronous (event-driven, eventually consistent). Asynchronous is
  likely correct; the citizen-facing wording needs care.
- Retention of `WITHDRAWN` pointers — they are evidence that something once existed, which
  is itself sensitive.
