# LLD: `registry`

| | |
|---|---|
| **HLD charter** | [hld/04-registry.md](../hld/04-registry.md) |
| **Migration range** | V60–V79 |
| **Package** | `com.samanvay.registry` |
| **Depends on** | `catalog.api`, `audit.api` |

---

## 1. A cycle this document avoids: discovery-consent is an event listener, not a call

`AccessAuthority` (in `consent`) calls `DiscoveryRegistry.locate(...)` — so `consent`
depends on `registry`. If `registry`'s discovery check for a `SENSITIVE` +
`CONSENT_REQUIRED_TO_DISCOVER` category needed to ask `consent` "has this citizen allowed
this requester to discover this category," that would be `registry → consent`, closing a
cycle with the dependency that already exists in the other direction.

**Fix, matching the pattern already used for `identity`/`connector` in
[hld/README.md](../hld/README.md#three-dependency-rules-that-keep-it-acyclic):** `registry`
listens for `ConsentGranted`/`ConsentRevoked` and maintains its own local, denormalized
record of who may discover what. Discovery then reads a local table — no live call into
`consent` at all.

## 2. Migration: `V60__registry_init.sql`

```sql
CREATE TABLE registry_category_policy (
    data_category     VARCHAR(60) PRIMARY KEY,
    sensitivity       VARCHAR(20) NOT NULL CHECK (sensitivity IN ('PUBLIC','RESTRICTED','SENSITIVE')),
    discovery_policy  VARCHAR(40) NOT NULL CHECK (discovery_policy IN ('VISIBLE','CONSENT_REQUIRED_TO_DISCOVER')),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE registry_clearance (
    requester_id  VARCHAR(60) NOT NULL,
    sensitivity   VARCHAR(20) NOT NULL CHECK (sensitivity IN ('PUBLIC','RESTRICTED','SENSITIVE')),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_id, sensitivity)
);

-- The local record that closes §1's cycle. Kept in sync purely by
-- listening to consent's own events, never by calling consent.
CREATE TABLE registry_discovery_grant (
    subject_id     UUID NOT NULL,
    requester_id   VARCHAR(60) NOT NULL,
    data_category  VARCHAR(60) NOT NULL,
    consent_id     UUID NOT NULL,     -- so a later ConsentRevoked can remove exactly this grant
    granted_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_id, requester_id, data_category)
);

CREATE TABLE registry_pointer (
    id               UUID PRIMARY KEY,
    subject_id       UUID NOT NULL,
    subject_type     VARCHAR(20) NOT NULL CHECK (subject_type IN ('PERSON','ORGANISATION','LAND_PARCEL')),
    department_code  VARCHAR(60) NOT NULL,
    data_category    VARCHAR(60) NOT NULL,
    source_ref       JSONB NOT NULL,        -- opaque locator: {"endpoint":"...","key":"..."} - never a payload
    issued_at        DATE,
    valid_until      DATE,
    as_of            TIMESTAMPTZ NOT NULL,
    freshness_mode   VARCHAR(20) NOT NULL CHECK (freshness_mode IN ('REALTIME','BATCH')),
    status           VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','EXPIRED','WITHDRAWN')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_id, department_code, data_category)
);
CREATE INDEX idx_pointer_subject ON registry_pointer (subject_id);
```

`subject_type = 'LAND_PARCEL'` costs nothing here — it's a value in a `CHECK` constraint's
list, added once in Phase 0 for a type that doesn't exist until Journey 3 (HLD's proof
that adding a canonical entity is configuration).

## 3. Discovery — the empty-vs-forbidden distinction, and where the local grant table is read

```java
package com.samanvay.registry.internal.service;

@Service
class DiscoveryRegistryService implements DiscoveryRegistry {

    @Override
    @Transactional(readOnly = true)
    public List<Pointer> discover(SubjectRef subject, RequesterRef requester, Set<DataCategory> categories) {
        List<Pointer> visible = new ArrayList<>();
        for (var category : categories) {
            var policy = policies.forCategory(category);
            if (!clearance.covers(requester, policy.sensitivity())) continue;   // excluded silently - see §4
            if (policy.discoveryPolicy() == DiscoveryPolicy.CONSENT_REQUIRED_TO_DISCOVER
                && !discoveryGrants.exists(subject.citizenId(), requester.id(), category)) continue;

            pointers.findBySubjectAndCategory(subject, category, requester.departmentCode())
                .ifPresent(visible::add);
        }
        audit.record(discoveryEntry(subject, requester, categories, visible.size()));   // the read itself is audited
        return visible;
    }

    @ApplicationModuleListener
    void on(ConsentGranted event) {
        for (var category : event.categories())
            discoveryGrants.upsert(event.citizenId(), event.requesterId(), category, event.consentId(), Instant.now());
    }

    @ApplicationModuleListener
    void on(ConsentRevoked event) {
        discoveryGrants.deleteByConsentId(event.consentId());
    }
}
```

## 4. Why empty, never forbidden

Returning an HTTP 403 or a `Denied` result for an uncleared requester would itself confirm
that a pointer exists in a category the requester isn't supposed to know about — the exact
leak [hld/04-registry.md §6](../hld/04-registry.md#6-key-decisions) exists to close. An
uncleared or unconsented category is silently dropped from the result set; the caller
cannot distinguish "nothing exists" from "something exists but you can't see it." Both the
success path and this silent exclusion are audited identically (§3's single `audit.record`
call at the end) — so the *system* always knows what happened, even though the *requester*
never can distinguish the two cases from the response alone.

## 5. Freshness and staleness

```java
package com.samanvay.registry.internal.service;

record Freshness(FreshnessMode mode, Instant asOf) {

    private static final Duration STALE_THRESHOLD = Duration.ofHours(6);   // default; per-category override possible later

    boolean isStale() {
        return mode == FreshnessMode.BATCH && Duration.between(asOf, Instant.now()).compareTo(STALE_THRESHOLD) > 0;
    }
}
```

`AccessAuthority` reads `Pointer.freshness()` and, when stale, checks
`JourneyCatalog.policy(journeyCode).acceptStale()` (`catalog.api`) before deciding whether
to proceed or route to manual verification — that branch lives in `consent`, not here;
this module's only job is to report freshness honestly, never to decide whether it's
acceptable.

## 6. Batch ingestion consumption

```java
@ApplicationModuleListener
void on(BatchRowIngested event) {
    pointers.upsert(event.dataSourceCode(), extractSubjectId(event.row()), extractCategory(event.row()),
        sourceRefFrom(event.row()), event.asOf(), FreshnessMode.BATCH);
}
```

`upsert` sets `as_of` from the **file's** timestamp (`event.asOf()`), never from the
moment this listener happens to run — a batch processed three hours late must still report
its true age, not a falsely-recent one.

## 7. Error handling

`discover()` and `locate()` raise nothing on an empty or excluded result — see §4. The
only exception this module defines:

| Exception (`registry.api`) | Raised when |
|---|---|
| `PointerWithdrawnException` | An internal consistency check finds a `WITHDRAWN` pointer being upserted again without an intervening `AVAILABLE` state — a signal the source system reinstated a record without telling us, worth surfacing rather than silently overwriting |

## 8. Events

### Published

| Event | Payload |
|---|---|
| `PointerUpserted` | `{subjectId, departmentCode, category, asOf}` |
| `PointerExpired` | `{subjectId, departmentCode, category}` |
| `StalenessThresholdBreached` | `{subjectId, departmentCode, category, age}` |

### Consumed

| Event | From | Reaction |
|---|---|---|
| `ConsentGranted` / `ConsentRevoked` | `consent` | Maintain `registry_discovery_grant` — see §1 |
| `LinkAsserted` | `identity` | Trigger discovery of pointers at the newly linked department |
| `BatchRowIngested` / `BatchIngestCompleted` | `connector` | Bulk pointer upsert, §6 |
| `DepartmentRegistered` | `catalog` | Seed default category policy rows for the new department |

## 9. Tests

| Test | Proves |
|---|---|
| `DiscoveryExclusionTest` | An uncleared requester and an unconsented `SENSITIVE` category both return an empty list — indistinguishable from "nothing exists" at the API surface |
| `DiscoveryGrantSyncTest` | `ConsentGranted` populates `registry_discovery_grant`; a subsequent `ConsentRevoked` for the same `consentId` removes exactly that row, no others |
| `NoCycleTest` | Static check: `registry.internal` has no compile-time dependency on `consent` (Modulith's own verify would already catch this, but a named test documents *why* it must never appear) |
| `FreshnessTest` | A `BATCH` pointer older than 6 hours reports `isStale() == true`; a `REALTIME` pointer never does |
| `BatchAsOfTest` | A row ingested from a file three hours after the file's own timestamp still reports `as_of` = the file's timestamp, not the ingestion time |
