# LLD: `audit`

| | |
|---|---|
| **HLD charter** | [hld/01-audit.md](../hld/01-audit.md) |
| **Migration range** | V1–V19 |
| **Package** | `com.samanvay.audit` |
| **Built** | Phase 0, first |

---

## 1. A correction this document makes to the HLD before writing any DDL

The HLD's tamper-evidence claim rests on: *"the application's database role holds no
`UPDATE` or `DELETE` grant on `audit_entry`."* That sentence is only true if the
application's role does **not own** the table.

**PostgreSQL table owners implicitly hold every privilege on their own tables, regardless
of any `REVOKE`.** A `REVOKE UPDATE ON audit_entry FROM samanvay` is a silent no-op if
`samanvay` is also the table's owner (which it would be, being the role that ran
`CREATE TABLE`). This would have made the entire security claim false while looking
correct in the migration file — exactly the kind of bug that survives to a demo and gets
caught by a judge who happens to know Postgres.

**Fix: two roles, not one.**

| Role | Used by | Privilege on `audit.*` |
|---|---|---|
| `samanvay_migrate` | Flyway only (`spring.flyway.*`), and it is `docker-compose`'s `POSTGRES_USER` | Owner — full DDL, created every table |
| `samanvay_app` | The running application (`spring.datasource.*`) | Explicit `SELECT, INSERT` only — never granted `UPDATE`/`DELETE`, and never the owner of anything |

Everywhere else in the system (`public.*`, i.e. every other module's tables), `samanvay_app`
gets full CRUD — it's only `audit.*` where the restriction matters. Rather than repeat a
`GRANT` in every future module's migration (V20, V40, V60, ... — a real way to
accidentally ship a module whose tables the app can't write to), `ALTER DEFAULT
PRIVILEGES` makes Postgres apply the grant automatically to every table
`samanvay_migrate` creates in `public` from now on. One rule, applies forever, nobody has
to remember it.

**This requires updating the two files already committed in Phase 0** — noted at the end
of this document (§9).

## 2. Migration: `V1__audit_init.sql`

```sql
-- V1__audit_init.sql
-- Creates audit.audit_entry / audit.audit_checkpoint, and the two-role
-- model the tamper-evidence claim depends on. See LLD §1 for why a
-- single shared role would have silently defeated the REVOKE below.

CREATE ROLE samanvay_app LOGIN PASSWORD '${appRolePassword}';

-- Every other module's tables land in `public`, created by later
-- migrations (V20+) still run as samanvay_migrate. Grant full DML now,
-- and have Postgres auto-grant it on every future table too.
GRANT USAGE ON SCHEMA public TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO samanvay_app;

CREATE SCHEMA audit AUTHORIZATION samanvay_migrate;

CREATE TABLE audit.audit_entry (
    seq             BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type      VARCHAR(20)  NOT NULL CHECK (actor_type IN ('CITIZEN','OFFICER','SYSTEM','ADMIN')),
    actor_id        VARCHAR(100) NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    subject_id      VARCHAR(100),
    resource        VARCHAR(200),
    department_id   VARCHAR(60),
    consent_id      UUID,
    grant_id        UUID,
    outcome         VARCHAR(20)  NOT NULL CHECK (outcome IN ('ALLOWED','DENIED','ERROR')),
    reason          VARCHAR(200),
    meta            JSONB        NOT NULL DEFAULT '{}',
    prev_hash       BYTEA        NOT NULL,
    hash            BYTEA        NOT NULL UNIQUE
);

CREATE INDEX idx_audit_entry_subject ON audit.audit_entry (subject_id);
CREATE INDEX idx_audit_entry_ts      ON audit.audit_entry (ts);
CREATE INDEX idx_audit_entry_grant   ON audit.audit_entry (grant_id);

CREATE TABLE audit.audit_checkpoint (
    seq             BIGSERIAL PRIMARY KEY,
    upto_entry_seq  BIGINT      NOT NULL REFERENCES audit.audit_entry(seq),
    root_hash       BYTEA       NOT NULL,
    signed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    signature       BYTEA       NOT NULL,
    published_ref   VARCHAR(500)
);

-- The actual guarantee: samanvay_app can append and read, nothing else.
-- Rewriting history needs samanvay_migrate's credential, which the
-- running application never holds.
GRANT USAGE ON SCHEMA audit TO samanvay_app;
GRANT SELECT, INSERT ON audit.audit_entry TO samanvay_app;
GRANT SELECT, INSERT ON audit.audit_checkpoint TO samanvay_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA audit TO samanvay_app;
ALTER DEFAULT PRIVILEGES FOR ROLE samanvay_migrate IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO samanvay_app;
```

`${appRolePassword}` is a Flyway placeholder (§9), never a literal password in the file.

## 3. Persistence: plain `JdbcTemplate`, not a JPA entity

Every other module uses Spring Data JPA. `audit` deliberately does not, for this table
specifically:

- We need certainty that the only SQL ever executed against `audit_entry` is an `INSERT`
  or a `SELECT`. Hibernate's session and dirty-checking machinery is one more layer that
  could, under some future refactor, issue an `UPDATE` nobody intended (fetch an entity,
  mutate a field, forget it's audit, `save()`). Plain `JdbcTemplate` has no such path —
  the only statements that exist are the ones written here.
- `JdbcTemplate` is already on the classpath transitively (`spring-jdbc`, pulled in by
  `spring-boot-starter-data-jpa`) — no new dependency.

This is the one deliberate exception to "every module uses JPA." It is not a precedent for
avoiding JPA elsewhere without an equally concrete reason.

```java
package com.samanvay.audit.internal.repository;

@Repository
class AuditEntryRepository {

    private final JdbcTemplate jdbc;

    AuditEntryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<byte[]> findLatestHash() {
        return jdbc.query(
            "SELECT hash FROM audit.audit_entry ORDER BY seq DESC LIMIT 1",
            rs -> rs.next() ? Optional.of(rs.getBytes("hash")) : Optional.empty());
    }

    long insert(AuditEntry entry, byte[] prevHash, byte[] hash, String canonicalJson) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                INSERT INTO audit.audit_entry
                    (actor_type, actor_id, action, subject_id, resource, department_id,
                     consent_id, grant_id, outcome, reason, meta, prev_hash, hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """, new String[]{"seq"});
            ps.setString(1, entry.actorType().name());
            ps.setString(2, entry.actorId());
            ps.setString(3, entry.action());
            ps.setString(4, entry.subjectId());
            ps.setString(5, entry.resource());
            ps.setString(6, entry.departmentId());
            ps.setObject(7, entry.consentId());
            ps.setObject(8, entry.grantId());
            ps.setString(9, entry.outcome().name());
            ps.setString(10, entry.reason());
            ps.setString(11, toJson(entry.meta()));
            ps.setBytes(12, prevHash);
            ps.setBytes(13, hash);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    List<AuditEntryRow> findRange(long fromSeqInclusive, long toSeqInclusive) { /* ... */ }
    Page<AuditEntryRow> search(AuditQuery query, Pageable page) { /* ... */ }
}
```

`AuditEntryRow` is a plain record mirroring the table columns exactly, mapped with a
`RowMapper` — no ORM annotations, since this is not a JPA entity.

## 4. The hash chain — the actual algorithm

```java
package com.samanvay.audit.internal.service;

@Service
class JdbcAuditService implements AuditService {

    // ponytail: one global advisory-lock key serializes every audit
    // append across the whole application. Correct at any throughput
    // this system will see (audit writes, not payload transfer). If it
    // ever measurably contends, shard the key (e.g. by day) rather than
    // removing the lock — the hash chain has no correctness without a
    // total order over appends.
    private static final long CHAIN_LOCK_KEY = 918_273_645L;
    private static final byte[] GENESIS_HASH =
        sha256("SAMANVAY_AUDIT_GENESIS".getBytes(StandardCharsets.UTF_8));

    private final AuditEntryRepository entries;
    private final JdbcTemplate jdbc;
    private final CanonicalJson canonicalJson;

    @Override
    @Transactional
    public AuditRef record(AuditEntry entry) {
        // Postgres advisory lock, scoped to this transaction: acquired
        // here, released automatically on commit or rollback. Ensures
        // no two concurrent appends can compute a hash against the same
        // prevHash.
        jdbc.query("SELECT pg_advisory_xact_lock(?)", new Object[]{CHAIN_LOCK_KEY}, rs -> null);

        byte[] prevHash = entries.findLatestHash().orElse(GENESIS_HASH);
        String canonical = canonicalJson.serialize(entry);
        byte[] hash = sha256(concat(prevHash, canonical.getBytes(StandardCharsets.UTF_8)));

        long seq = entries.insert(entry, prevHash, hash, canonical);
        return new AuditRef(seq, hash);
    }

    @Override
    public VerificationResult verify(long fromSeq, long toSeq) {
        List<AuditEntryRow> rows = entries.findRange(fromSeq, toSeq);
        byte[] expectedPrev = fromSeq == 1
            ? GENESIS_HASH
            : entries.findHashAt(fromSeq - 1).orElseThrow(() -> new ChainGapException(fromSeq - 1));

        for (AuditEntryRow row : rows) {
            if (!Arrays.equals(row.prevHash(), expectedPrev)) {
                return VerificationResult.failed(row.seq(), "prev_hash does not match preceding entry");
            }
            byte[] recomputed = sha256(concat(row.prevHash(),
                canonicalJson.serialize(row.toEntry()).getBytes(StandardCharsets.UTF_8)));
            if (!Arrays.equals(recomputed, row.hash())) {
                return VerificationResult.failed(row.seq(), "hash does not match entry content — entry was modified");
            }
            expectedPrev = row.hash();
        }
        return VerificationResult.ok(fromSeq, toSeq);
    }
}
```

### `CanonicalJson` — the part that must be boring and exact

Lives in `com.samanvay.shared`, not `audit.internal` — `consent`'s `AccessGrant` signing
needs the identical guarantee (see [`../LLD.md` §6](../LLD.md#6-shared--what-deliberately-lives-here-and-the-rule-against-growing-it)),
so this is written once and used by both.

```java
package com.samanvay.shared;

public final class CanonicalJson {

    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)   // Instant -> ISO-8601 string, not epoch millis
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)  // meta{} key order is otherwise undefined
        .build();

    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("value must always be serializable", e);
        }
    }
}
```

Two settings do the actual work: `ORDER_MAP_ENTRIES_BY_KEYS` makes the `meta` map's key
order deterministic (a Java `HashMap`'s iteration order is not stable across JVM
restarts — this is precisely what would otherwise make the same logical entry hash
differently on different runs). `WRITE_DATES_AS_TIMESTAMPS = false` makes `Instant` fields
serialize as a fixed-format string rather than an epoch-millis number, avoiding any
ambiguity from clock precision or numeric formatting. `AuditEntry`'s own top-level fields
need no special handling — a Java record serializes its components in declaration order,
which is already fixed.

### `ChainCheckpointScheduler`

```java
package com.samanvay.audit.internal.service;

@Component
class ChainCheckpointScheduler {

    private final AuditEntryRepository entries;
    private final CheckpointRepository checkpoints;
    private final SecretStore secretStore;   // consent module's port, reused here — see HLD P6

    @Scheduled(cron = "0 0 * * * *")   // hourly
    void createCheckpoint() {
        long uptoSeq = entries.currentMaxSeq();
        byte[] rootHash = entries.findHashAt(uptoSeq).orElseThrow();
        byte[] signature = sign(rootHash, uptoSeq, secretStore.resolve("audit-checkpoint-signing-key"));
        checkpoints.insert(uptoSeq, rootHash, signature);
    }
}
```

If the signing key is unavailable, this throws and the scheduled run is skipped —
per [hld/01-audit.md §7](../hld/01-audit.md#7-failure-modes), a missed checkpoint is an
alarm, not a fallback to an unsigned one.

## 5. Sequence: recording an entry (the Phase 0 acceptance path)

```
Caller (any module, in its own @Transactional method)
        │
        │ auditService.record(entry)
        ▼
JdbcAuditService.record()
        │
        ├─▶ pg_advisory_xact_lock(918273645)      — blocks until free, released at commit
        ├─▶ AuditEntryRepository.findLatestHash()
        ├─▶ CanonicalJson.serialize(entry)
        ├─▶ sha256(prevHash || canonical)
        ├─▶ AuditEntryRepository.insert(...)       — one SQL INSERT, same transaction as caller
        │
        ▼
   returns AuditRef(seq, hash)
        │
        ▼
Caller's transaction commits  →  business change AND audit entry commit together, or neither does
```

A temporary diagnostic endpoint exists purely to exercise this path end to end for the
Phase 0 "done when" criterion, and is expected to be removed once a real audited operation
exists (Phase 1's consent grant issuance):

```java
package com.samanvay.audit.internal.web;

@RestController
@RequestMapping("/internal/audit")
class AuditPingController {   // Phase 0 scaffolding — remove once Phase 1 has a real audited write

    @PostMapping("/ping")
    AuditRef ping(@RequestBody PingRequest req) {
        return auditService.record(new AuditEntry(
            ActorType.SYSTEM, "phase0-ping", "PING", req.subjectId(), null, null,
            null, null, Outcome.ALLOWED, null, Map.of()));
    }
}
```

## 6. Error handling

| Exception (`audit.api`) | Raised when |
|---|---|
| `ChainGapException` | `verify()` is asked to start from a `seq` whose predecessor doesn't exist |
| `CheckpointSigningException` | The signing key can't be resolved at checkpoint time |

`record()` itself throws nothing special on the happy path — a database error propagates
as Spring's ordinary `DataAccessException`, which rolls back the caller's transaction.
That propagation, not a caught-and-logged fallback, **is** the design: see
[HLD §4.3](../LLD.md#43-what-is-never-caught-and-swallowed).

## 7. Events

None. Audit is a sink — see [hld/01-audit.md §5](../hld/01-audit.md#5-events).

## 8. Tests

| Test | Proves |
|---|---|
| `CanonicalJsonTest` (in `shared`, exercised by both `audit` and `consent`) | Same logical entry serializes identically regardless of `meta`/field map insertion order; stable across two separate `ObjectMapper` instances |
| `HashChainServiceTest` (unit, fake repository, no Spring context) | `record()` computes `hash = sha256(prevHash \|\| canonical)` correctly; `verify()` detects a single altered field, a deleted row (seq gap), and a swapped `prev_hash` |
| `AuditEntryRepositoryIT extends PostgresIntegrationTest` | Real insert/read against Testcontainers Postgres; a real chain of 5 entries verifies clean |
| `AuditRolePrivilegeIT extends PostgresIntegrationTest` | **The test that actually proves §1's fix works**: opens a second JDBC connection *as `samanvay_app`* and asserts `UPDATE audit.audit_entry SET reason = 'x'` throws a permission-denied `SQLException`. Without this test, a future migration could silently reintroduce the ownership bug this document opened by fixing |
| `ChainCheckpointSchedulerTest` | Checkpoint signature verifies against the public key; a tampered `root_hash` fails verification |
| `AuditPingControllerIT` | The literal Phase 0 acceptance criterion: an HTTP call produces a chained, verifiable entry |

## 9. Required changes to already-committed files

Two files from the Phase 0 bootstrap commit need updating for §1's two-role model to
exist at all:

**`docker-compose.yml`** — rename the Postgres superuser so its name matches its actual
role:
```yaml
environment:
  POSTGRES_DB: samanvay
  POSTGRES_USER: samanvay_migrate
  POSTGRES_PASSWORD: samanvay_migrate
```

**`application.yml`** — split the runtime connection from the migration connection:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/samanvay
    username: samanvay_app
    password: ${SAMANVAY_APP_DB_PASSWORD:samanvay_app_dev_password}
  flyway:
    enabled: true
    url: jdbc:postgresql://localhost:5432/samanvay
    user: samanvay_migrate
    password: ${SAMANVAY_MIGRATE_DB_PASSWORD:samanvay_migrate}
    placeholders:
      appRolePassword: ${SAMANVAY_APP_DB_PASSWORD:samanvay_app_dev_password}
```

Dev-only default passwords, overridable by environment variable — never a real credential
committed, same convention the bootstrap commit already established for the single-role
setup.
