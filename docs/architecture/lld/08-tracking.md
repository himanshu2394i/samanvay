# LLD: `tracking`

| | |
|---|---|
| **HLD charter** | [hld/08-tracking.md](../hld/08-tracking.md) |
| **Migration range** | V140–V159 |
| **Package** | `com.samanvay.tracking` |
| **Depends on** | `catalog.api`, `audit.api` — and `orchestration`'s event *types* only, never a direct call |

---

## 1. Two open questions closed

**SLA clocks are computed by `orchestration`, stored here.** `tracking` is a pure
projection ([hld/08-tracking.md §6](../hld/08-tracking.md#6-key-decisions)); computing a
due-time from `catalog_journey.policy` would make it something more than that, and would
duplicate logic `orchestration` already has to have (it's the one starting the step
against the journey's policy in the first place). `orchestration` includes `slaDueAt` in
the events it publishes; this module only ever stores and displays a value it was handed.

**Reference numbers come from one global Postgres sequence, never a per-year-reset
counter.** The year in `MH-SCH-2026-000123` is for human legibility at a counter, not a
per-year tally — resetting it annually would need extra machinery (a job to reset the
sequence, a race if that job runs late) to buy nothing anyone asked for.

```java
// ponytail: one global sequence, numeric suffix never resets per year.
// Revisit only if a real requirement demands annual reset - not a
// concern this system has today.
```

## 2. Migration: `V140__tracking_init.sql`

```sql
CREATE SEQUENCE tracking_reference_seq START 1;

CREATE TABLE tracking_application (
    id                   UUID PRIMARY KEY,
    reference_no         VARCHAR(40) NOT NULL UNIQUE,
    citizen_id           UUID NOT NULL,
    journey_code         VARCHAR(60) NOT NULL,
    process_instance_id  VARCHAR(100) NOT NULL,
    status               VARCHAR(30) NOT NULL CHECK (status IN
        ('SUBMITTED','PARTIALLY_VERIFIED','VERIFIED','APPROVED','REJECTED','CLOSED')),
    submitted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sla_due_at           TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ
);
CREATE INDEX idx_tracking_app_citizen ON tracking_application (citizen_id);

CREATE TABLE tracking_step (
    id               UUID PRIMARY KEY,
    application_id   UUID NOT NULL REFERENCES tracking_application(id),
    step_code        VARCHAR(60) NOT NULL,
    department_code  VARCHAR(60),
    status           VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','IN_PROGRESS','COMPLETED','PENDING_SOURCE','AUTHORIZATION_WITHDRAWN','FAILED')),
    outcome          VARCHAR(500),
    source           VARCHAR(20) NOT NULL CHECK (source IN ('API','BATCH','CITIZEN_UPLOAD')),
    data_as_of       TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    sla_due_at       TIMESTAMPTZ,
    audit_ref        BIGINT,     -- soft ref to audit.audit_entry(seq) - "show me the proof" is one lookup
    UNIQUE (application_id, step_code)
);
```

## 3. The projector — every write is idempotent on redelivery

```java
package com.samanvay.tracking.internal.service;

@Component
class TrackingProjector {

    @ApplicationModuleListener
    void on(JourneyStarted event) {
        String ref = referenceGenerator.next(event.journeyCode());
        applications.insert(event.instanceId(), ref, event.citizenId(), event.journeyCode(),
            event.processInstanceId(), Status.SUBMITTED, event.slaDueAt());
        events.publishEvent(new ApplicationReferenceIssued(ref, event.citizenId()));
    }

    @ApplicationModuleListener
    void on(StepCompleted event) {
        // ON CONFLICT means Modulith's at-least-once delivery can never
        // double-write a step - a redelivered event updates the same row
        // to the same values, not a second row.
        steps.upsert(event.instanceId(), event.stepCode(), StepStatus.COMPLETED, event.outcome(),
            Source.API, null, Instant.now(), event.auditRef());
        recomputeAggregateStatus(event.instanceId());
    }

    @ApplicationModuleListener
    void on(StepPendingSource event) {
        steps.upsert(event.instanceId(), event.stepCode(), StepStatus.PENDING_SOURCE, null,
            Source.API, null, null, event.slaDueAt());
        applications.updateStatus(event.instanceId(), Status.PARTIALLY_VERIFIED);   // never FAILED here
    }

    @ApplicationModuleListener
    void on(ConsentRevoked event) {
        steps.markAuthorizationWithdrawn(event.consentId());
    }

    @ApplicationModuleListener
    void on(SlaBreached event) {
        applications.recordBreach(event.instanceId(), event.stepCode(), event.dueAt());
    }
}
```

```sql
-- the upsert this relies on
INSERT INTO tracking_step (id, application_id, step_code, status, outcome, source, data_as_of, completed_at, audit_ref)
VALUES (?,?,?,?,?,?,?,?,?)
ON CONFLICT (application_id, step_code)
DO UPDATE SET status = EXCLUDED.status, outcome = EXCLUDED.outcome,
              completed_at = EXCLUDED.completed_at, audit_ref = EXCLUDED.audit_ref;
```

## 4. Rebuildable, because Modulith's event log already makes it possible

Modulith's Event Publication Registry durably persists every published event (with its
serialized payload) whether or not this module is even running at the time. That means
`tracking`'s tables could, in principle, be fully reconstructed by replaying that log —
which is a useful property to have inherited for free, and worth stating so nobody
"fixes" a `tracking` data issue by hand-editing rows when replaying is available. **No
replay tool is being built now** — nothing has needed one yet, and building one on
spec would be exactly the kind of unrequested scaffolding this project's own principles
argue against. If reconciliation is ever genuinely needed, the mechanism already exists;
only the tool would need writing.

## 5. Error handling

`tracking` raises nothing on its own listener paths — a malformed event is a defect in the
*publisher*, not something this module recovers from by catching and hiding it (see
[LLD.md §4.3](../LLD.md#43-what-is-never-caught-and-swallowed)). The one place it does
raise:

| Exception (`tracking.api`) | Raised when |
|---|---|
| `ApplicationNotFoundException` | `byReference()`/`steps()` given an unknown reference |

## 6. Events

### Published
`ApplicationReferenceIssued` — `{referenceNo, citizenId}`

### Consumed
Everything in [LLD.md §7.2/§7.3](../LLD.md#72-parallel-fan-out-and-degraded-mode-hld-62)
— `JourneyStarted`, `StepCompleted`, `StepFailed`, `StepPendingSource`,
`ApplicationStateChanged`, `SlaBreached`, `ConsentRevoked`.

## 7. Tests

| Test | Proves |
|---|---|
| `IdempotentProjectionTest` | Delivering the same `StepCompleted` event twice leaves exactly one `tracking_step` row |
| `ReferenceGeneratorConcurrencyTest` | 100 concurrent `next()` calls produce 100 distinct reference numbers, no gaps depended upon, no duplicates |
| `NeverFailedWhilePartialTest` | A `StepPendingSource` event moves the application to `PARTIALLY_VERIFIED`, never `FAILED`, regardless of how many other steps are still pending |
| `ProjectionRebuildTest` | Replaying a captured sequence of events from empty tables reproduces the same final state as processing them live |
| `NoOrchestrationDependencyTest` | `tracking.internal` has no compile-time reference to any `orchestration.internal` type — only event record types from `orchestration.api` |
