# Module: `audit`

| | |
|---|---|
| **Plane** | Cross-cutting |
| **Owner** | Technical lead |
| **Phase** | 0 — first module built |
| **Depends on** | `SecretStore` (infrastructure) |
| **Depended on by** | Every module |
| **HLD context** | [§5.8](../HLD.md#58-audit) · [§9.4](../HLD.md#94-tamper-evident-audit) |

---

## 1. Purpose

Record every security-relevant event in a tamper-evident chain, and prove that the chain
has not been altered.

This module is built **first**, before any business functionality. Retrofitting an audit
trail means revisiting every write path in the system, and a write path that was never
audited is indistinguishable from one that was audited and then erased.

## 2. Responsibilities

### Owns

- The append-only `audit_entry` chain and its hashing scheme
- Periodic checkpoint creation and signing
- Chain verification between any two checkpoints
- The `audit` PostgreSQL schema and its restricted database role

### Explicitly not responsible for

- **Deciding what is auditable.** Callers decide; this module records. A module that
  forgets to audit is that module's defect, caught in its own acceptance criteria.
- **Application logging.** Structured logs are operational; audit entries are evidence.
  They have different retention, different access control and different integrity
  requirements.
- **Metrics.** Micrometer handles those.
- **Purging.** Audit entries are never deleted. Archival of grants (§5.5) is the `consent`
  module's concern and is itself audited.

## 3. Public interface — `com.samanvay.audit.api`

```java
public interface AuditService {

    /** Records an entry in the current transaction. Never swallows failure. */
    AuditRef record(AuditEntry entry);

    /** Verifies the chain between two checkpoints, or from the last checkpoint to head. */
    VerificationResult verify(long fromSeq, long toSeq);

    Optional<Checkpoint> latestCheckpoint();

    Page<AuditEntry> search(AuditQuery query, Pageable page);   // AUDITOR role only
}

public record AuditEntry(
    ActorType actorType, String actorId,       // CITIZEN | OFFICER | SYSTEM | ADMIN
    String action,                             // GRANT_ISSUED, DATA_ACCESSED, …
    String subjectId,                          // the citizen the event concerns
    String resource,
    String departmentId,
    UUID   consentId,
    UUID   grantId,
    Outcome outcome,                           // ALLOWED | DENIED | ERROR
    String reason,
    Map<String, Object> meta
) {}
```

`AuditRef` returns `seq` and `hash` so a caller can reference its own audit entry — for
example, `tracking_step` recording which audit entry corresponds to a step's data fetch.

## 4. Data owned

Separate PostgreSQL schema `audit`, with its own database role.

| Table | Purpose |
|---|---|
| `audit_entry` | The chain. `seq`, `ts`, actor, action, subject, resource, department, `consent_id`, `grant_id`, outcome, reason, `meta` jsonb, `prev_hash`, `hash` |
| `audit_checkpoint` | `seq`, `root_hash`, `signed_at`, `signature`, `published_ref` |

**The application's database role holds no `UPDATE` or `DELETE` grant on `audit_entry`.**
This is the functional reason `audit` has its own schema — per-table grant management in a
shared schema becomes unmanageable. Migration must include the `REVOKE`, and a test must
assert that an attempted `UPDATE` fails.

## 5. Events

### Published
None. Audit is a sink, not a source. Publishing events from it would create a second
delivery path for evidence and a second thing that can fail.

### Consumed
None. Callers invoke `AuditService` directly and synchronously.

## 6. Key decisions

### Entries are written in the caller's transaction

A data access and its audit record commit together or not at all.

This is the specific reason `audit` is a module inside the monolith rather than a service.
As a remote call, every audited operation becomes a distributed transaction, and its
failure mode is **unaudited access** — precisely the thing the module exists to prevent.

### The chain alone is insufficient — three measures, not one

An attacker with write access can rewrite entry 400 and recompute every hash after it. The
chain then verifies perfectly and proves nothing.

| Measure | Defeats |
|---|---|
| Signed checkpoints (key from `SecretStore`, outside the DB) | An attacker with full database access |
| No `UPDATE`/`DELETE` grant for the application role | Application-level compromise |
| External checkpoint publication | Collusion at the platform operator |

### Canonical serialization is part of the security boundary

```java
byte[] canonical = CanonicalJson.serialize(entry);   // deterministic key ordering
entry.hash = sha256(concat(prevHash, canonical));
```

If serialization is not deterministic, verification produces false failures and the whole
mechanism gets disabled by whoever is on call. Key ordering, number formatting and
timestamp precision are all fixed and covered by a test.

### Not blockchain

A signed hash chain on append-only storage provides equivalent tamper-evidence without
consensus overhead. This is a stated position, not an omission.

### The verifier is a UI screen

Editing a row live and watching verification fail demonstrates the property in fifteen
seconds. A CLI script does not.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Audit write fails | **The business transaction rolls back.** Never degrade to unaudited operation |
| Signing key unavailable at checkpoint time | Checkpoint deferred, alarm raised, chain continues. Entries remain verifiable once signing resumes |
| Verification finds a mismatch | Report the exact `seq` range; do not attempt repair |
| Chain gap (missing `seq`) | Treated as tampering, reported as such |
| Concurrent writes racing on `prev_hash` | Serialize appends — see §9 |

## 8. Acceptance criteria

- [ ] A trivial endpoint produces a chained entry whose hash verifies
- [ ] `UPDATE audit_entry SET …` as the application role **fails**
- [ ] Checkpoints are created hourly and signed with a `SecretStore` key
- [ ] `verify()` detects a single altered field, a deleted row, and a `seq` gap
- [ ] The audit explorer UI can verify a range and display a failure visually
- [ ] `AUDITOR` role can read the chain and **cannot** read citizen data via any endpoint
- [ ] Canonical serialization is stable across JVM restarts and key insertion order
- [ ] A rolled-back business transaction leaves no audit entry

## 9. Open questions for LLD

- **Append serialization.** Chained hashes require a total order. Options: a Postgres
  advisory lock per append, a single-writer queue, or `SERIALIZABLE` isolation on the
  audit transaction. Needs a decision plus a concurrency test — this is the module's only
  real contention point and the most likely source of a production stall.
- Checkpoint interval under load: hourly by wall clock, or every N entries, or both.
- External publication target for the demo — a second container's filesystem is probably
  sufficient and honest.
