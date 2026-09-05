# Module: `tracking`

| | |
|---|---|
| **Plane** | Data |
| **Owner** | E |
| **Phase** | 1 |
| **Depends on** | `catalog`, `audit` — and orchestration's **event types** only |
| **Depended on by** | Nothing. Read by the API layer |
| **HLD context** | [§5.7](../HLD.md#57-tracking) |

---

## 1. Purpose

Give the citizen **one reference number** and one status view for something that happens
across several departments — and give the officer the same view with provenance attached.

This module is the direct answer to the problem statement's *"track applications across
different portals"*. It is small, and it carries a disproportionate share of the perceived
value.

## 2. Responsibilities

### Owns

- The citizen-facing application reference (`MH-SCH-2026-000123`)
- Aggregated cross-department application status
- Per-step status with source provenance
- SLA due times and breach records, as displayed

### Explicitly not responsible for

- **Executing anything.** It is a projection built from events.
- **Deciding status.** `orchestration` decides; `tracking` records and presents.
- **Authorization.** The API layer enforces that a citizen sees only their own
  applications and an officer only their department's steps.
- **Notifying anyone.** `notifications` consumes the same events independently.

## 3. Public interface — `com.samanvay.tracking.api`

```java
public interface ApplicationTracking {
    ApplicationView byReference(String referenceNo);
    Page<ApplicationSummary> forCitizen(UUID citizenId, Pageable p);
    Page<ApplicationSummary> forDepartment(String departmentCode, StatusFilter f, Pageable p);
    List<StepView> steps(String referenceNo);
}

public record StepView(
    String stepCode, String departmentCode,
    StepStatus status,                 // PENDING | IN_PROGRESS | COMPLETED | PENDING_SOURCE
                                       // | AUTHORIZATION_WITHDRAWN | FAILED
    Instant startedAt, Instant completedAt, Instant slaDueAt,
    Source source,                     // API | BATCH | CITIZEN_UPLOAD
    Optional<String> outcome,
    Optional<Instant> dataAsOf         // freshness, for batch-sourced evidence
) {}
```

## 4. Data owned

| Table | Notes |
|---|---|
| `tracking_application` | `reference_no`, citizen, journey code, `process_instance_id`, status, `submitted_at`, `sla_due_at`, `closed_at` |
| `tracking_step` | application, step code, department, status, timestamps, `sla_due_at`, outcome, **`source`**, `data_as_of`, `audit_ref` |

`tracking_step.source` and `data_as_of` are what let an approving officer see *how* each
piece of evidence was obtained and *how old* it is. `audit_ref` links a step to the audit
entry for its data access, so "show me the proof" is one join.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `ApplicationReferenceIssued` | `notifications` |

### Consumed

All of them. This module is a projection.

| Event | Reaction |
|---|---|
| `JourneyStarted` | Create application, issue reference |
| `StepCompleted` / `StepFailed` | Update step, record source and freshness |
| `StepPendingSource` | Mark step `PENDING_SOURCE` |
| `ApplicationStateChanged` | Update aggregate status |
| `SlaBreached` | Record breach with cause |
| `ConsentRevoked` | Mark affected steps `AUTHORIZATION_WITHDRAWN` |

## 6. Key decisions

### A projection, not a source of truth

`orchestration` owns process state; `tracking` owns the *view*. Two modules writing
application status would eventually disagree, and the one the citizen sees would be the
wrong one.

Consequence: `tracking` can be rebuilt from the event log. That is a useful property to
have and a cheap one to preserve.

### Event-driven, so no cycle exists

`orchestration` publishes; `tracking` listens. A direct call from `orchestration` would
require it to depend on `tracking`, whose event handlers depend on orchestration's types —
the first dependency cycle in the system.

### The reference number is human-usable

`MH-SCH-2026-000123` — jurisdiction, journey, year, sequence. Read aloud over a phone at a
counter, which is how these are actually used. Not a UUID.

### Source provenance is per step, not per application

An officer approving a scholarship needs to know that income came from Revenue's API two
minutes ago while the property record came from a nightly file fourteen hours old. An
application-level provenance field cannot express that, and the difference is exactly what
an approval decision turns on.

### Eventual consistency is acceptable here, and must be visible

The projection may lag the engine by milliseconds. That is fine for a status view. It is
not fine to present a stale status as authoritative during a state change, so the view
carries its own `as_of`.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Event delivery delayed | View lags briefly. Modulith's publication registry retries; nothing is lost |
| Event handler throws | Publication remains incomplete and is retried on restart — the projection self-heals |
| Duplicate event delivery | Handlers are idempotent, keyed on (application, step, event id) |
| Reference collision | Unique constraint; sequence allocation is transactional |
| Application exists in the engine but not in the projection | Detectable by reconciliation against `orchestration_instance`; a rebuild path exists |

## 8. Acceptance criteria

- [ ] One reference number covers a multi-department journey end to end
- [ ] A citizen sees only their own applications; an officer only their department's steps
- [ ] Step view shows `source` and, for batch evidence, `data_as_of`
- [ ] `PENDING_SOURCE` is displayed distinctly from `FAILED` in the citizen view
- [ ] Duplicate event delivery does not double-write a step
- [ ] The projection can be rebuilt from the event log and matches the engine
- [ ] SLA breach is visible with a cause, not just a red flag
- [ ] `audit_ref` on a step resolves to the audit entry for that data access

## 9. Open questions for LLD

- Whether SLA clocks are computed in `orchestration` and stored here, or computed here from
  timestamps. Single ownership required; storing computed values here is simpler to display
  and easier to get wrong on timezone boundaries.
- Reference-number allocation under concurrency: database sequence versus a per-year
  counter table.
- Retention of closed applications, and whether the citizen view keeps history indefinitely.
- Whether the officer's consolidated view belongs here or is composed at the API layer from
  `tracking` + `registry` + a live fetch. Composing at the API layer keeps this module a
  pure projection; needs a decision before the officer console is built.
