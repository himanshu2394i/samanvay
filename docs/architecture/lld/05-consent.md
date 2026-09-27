# LLD: `consent` (+ `AccessAuthority`)

| | |
|---|---|
| **HLD charter** | [hld/05-consent.md](../hld/05-consent.md) |
| **Migration range** | V80–V99 |
| **Package** | `com.samanvay.consent` |
| **Depends on** | `identity.api`, `registry.api`, `catalog.api`, `audit.api`, `shared` |

---

## 1. Two open questions this document closes

The HLD deliberately left two decisions for the LLD. Both are resolved here, before any
code exists to disagree with the resolution.

**Signature scheme: Ed25519, not JWT.** The JDK has had native Ed25519 support since
Java 15 (`KeyPairGenerator`/`Signature.getInstance("Ed25519")`) — no third-party crypto
dependency. A JWT library's main feature, letting the *token* declare which algorithm to
verify with, is precisely the source of real historical vulnerabilities (`alg: none`,
HMAC/RSA confusion): the verifier trusts a field the token itself supplies. Here, both the
signer and the verifier are code we write, and there is exactly one algorithm ever in use
— JWT's flexibility buys nothing and adds an attack class we'd then have to explicitly
close (pin the expected algorithm, reject anything else). Plain Ed25519 has no such
surface: the verifier is hardcoded to one algorithm and cannot be told otherwise.

**`authorize()` short-circuits on the first failing check, in a fixed order** (link →
consent → registry), rather than evaluating every check and returning all failures. Two
reasons: it's simpler to reason about and test, and it leaks less to a hostile requester —
returning every reason a request failed for tells a probing caller more about internal
state (whether a link exists, whether a pointer exists) than returning just the first
blocker does. The one exception: a `NO_CONSENT` denial still carries the actionable
`ConsentRequest` remedy, because that information is for the *citizen*, not the requester,
and offering it is the entire point of the consent flow.

## 2. Migration: `V80__consent_init.sql`

```sql
CREATE TABLE consent_request (
    id                  UUID PRIMARY KEY,
    subject_citizen_id  UUID NOT NULL,              -- soft ref to identity_citizen, see LLD.md §3.2
    requester_id        VARCHAR(60) NOT NULL,        -- soft ref to catalog_department
    purpose_code        VARCHAR(60) NOT NULL,
    purpose_text        VARCHAR(500) NOT NULL,
    data_categories     TEXT[] NOT NULL,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','GRANTED','DENIED','EXPIRED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at        TIMESTAMPTZ
);

CREATE TABLE consent_artifact (
    id                  UUID PRIMARY KEY,
    subject_citizen_id  UUID NOT NULL,
    requester_id        VARCHAR(60) NOT NULL,
    purpose_code        VARCHAR(60) NOT NULL,
    purpose_text        VARCHAR(500) NOT NULL,
    data_categories     TEXT[] NOT NULL,
    granularity         VARCHAR(20) NOT NULL CHECK (granularity IN ('ONE_TIME','RECURRING')),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    frequency_limit     INT,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    version             INT NOT NULL DEFAULT 1,
    citizen_auth_ref    VARCHAR(200) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_artifact_subject ON consent_artifact (subject_citizen_id);
CREATE INDEX idx_consent_artifact_lookup  ON consent_artifact (requester_id, subject_citizen_id, status);

CREATE TABLE consent_event (
    id            UUID PRIMARY KEY,
    consent_id    UUID NOT NULL REFERENCES consent_artifact(id),   -- same module: a real FK is fine here
    event_type    VARCHAR(30) NOT NULL CHECK (event_type IN ('REQUESTED','GRANTED','REVOKED','EXPIRED')),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail        JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE consent_access_grant (
    id                  UUID PRIMARY KEY,
    nonce               BYTEA NOT NULL UNIQUE,
    consent_id          UUID NOT NULL REFERENCES consent_artifact(id),
    consent_version     INT NOT NULL,
    subject_citizen_id  UUID NOT NULL,
    requester_id        VARCHAR(60) NOT NULL,
    data_category       VARCHAR(60) NOT NULL,
    department_id       VARCHAR(60) NOT NULL,
    connector_ref       VARCHAR(100) NOT NULL,
    purpose_code        VARCHAR(60) NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    signature           BYTEA NOT NULL
);
CREATE INDEX idx_grant_expiry ON consent_access_grant (expires_at);
```

`consent_access_grant` — named per [LLD.md §3.1](../LLD.md#31-one-schema-one-exception),
correcting the HLD's original `access_grant` (see [hld/05-consent.md](../hld/05-consent.md),
fixed alongside this document).

## 3. Entities and repositories

Standard JPA here — nothing about `consent` needs the raw-JDBC treatment `audit` needed;
there is no append-only integrity property to protect, and normal Hibernate lifecycle
(fetch a `ConsentArtifact`, call `revoke()`, save) is exactly the right shape for
`version`-based optimistic concern... except **`version` here is a domain field
(`consent_version`, exposed to grant verification), not JPA's own `@Version` optimistic
lock column.** Do not annotate it `@Version` — that would make Hibernate manage it
silently and increment it on unrelated field updates, breaking the exact semantics
`consent`'s revocation mechanism depends on (**only** a grant/revoke/expire transition
increments it, nothing else). Increment it explicitly in the service method that performs
those three transitions, never implicitly.

```java
package com.samanvay.consent.internal.domain;

@Entity
@Table(name = "consent_artifact")
class ConsentArtifactEntity {
    @Id UUID id;
    UUID subjectCitizenId;
    String requesterId;
    String purposeCode;
    String purposeText;
    @Type(ListArrayType.class) List<String> dataCategories;   // maps TEXT[]
    @Enumerated(EnumType.STRING) Granularity granularity;
    Instant validFrom, validUntil;
    Integer frequencyLimit;
    @Enumerated(EnumType.STRING) ConsentStatus status;
    int version;                       // plain int column - see note above, NOT @Version
    String citizenAuthRef;
    Instant createdAt, updatedAt;
}
```

## 4. Signing and verification

```java
package com.samanvay.consent.internal.service;

@Component
class GrantSigner {

    private final PrivateKey signingKey;      // Ed25519, resolved once at startup
    private final CanonicalJson canonicalJson; // shared - see LLD.md §6

    GrantSigner(SecretStore secretStore, CanonicalJson canonicalJson) {
        var secret = secretStore.resolve("consent-grant-signing-key");
        this.signingKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(secret.bytes()));
        this.canonicalJson = canonicalJson;
    }

    byte[] sign(UnsignedGrant grant) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(signingKey);
            sig.update(canonicalJson.serialize(grant).getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new GrantSigningException(e);
        }
    }
}
```

```java
package com.samanvay.consent.internal.service;

/**
 * The connector module's ONLY view of grant cryptography. Holds the
 * PUBLIC key only - constructed from a different SecretStore entry than
 * GrantSigner above, so there is no code path by which this class could
 * ever hold the private key, even by accident. This is what makes
 * "connector can verify but cannot mint" a property of the object graph,
 * not just a comment.
 */
@Component
class Ed25519GrantVerifier implements AccessGrantVerifier {

    private final PublicKey verifyingKey;
    private final AccessGrantRepository grants;
    private final CanonicalJson canonicalJson;

    Ed25519GrantVerifier(SecretStore secretStore, AccessGrantRepository grants, CanonicalJson canonicalJson) {
        var secret = secretStore.resolve("consent-grant-verifying-key");   // PUBLIC key entry
        this.verifyingKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(secret.bytes()));
        this.grants = grants;
        this.canonicalJson = canonicalJson;
    }

    @Override
    public void verifyOrThrow(AccessGrant grant, DataCategory expectedCategory, String expectedConnectorRef) {
        if (Instant.now().isAfter(grant.expiresAt()))
            throw new InvalidGrantException(grant.id(), "expired");
        if (!grant.category().equals(expectedCategory) || !grant.connectorRef().equals(expectedConnectorRef))
            throw new InvalidGrantException(grant.id(), "category/connector mismatch");
        if (grants.isNonceUsed(grant.nonce()))
            throw new InvalidGrantException(grant.id(), "nonce already used");
        if (!currentConsentVersionMatches(grant))
            throw new InvalidGrantException(grant.id(), "consent revoked or changed since issuance");

        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(verifyingKey);
            sig.update(canonicalJson.serialize(grant.withoutSignature()).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(grant.signature()))
                throw new InvalidGrantException(grant.id(), "signature invalid");
        } catch (GeneralSecurityException e) {
            throw new InvalidGrantException(grant.id(), "signature verification error", e);
        }

        grants.markUsed(grant.nonce(), Instant.now());   // burns the nonce - see §5 for the race this closes
    }
}
```

**Order matters in `verifyOrThrow`:** cheap checks (expiry, category match) run before the
nonce-and-signature checks, so a malformed or replayed grant fails fast without touching
the database or running a cryptographic operation. `markUsed` runs **last**, only after
signature verification succeeds — burning the nonce for a grant that turns out to be
forged would let a genuinely valid retry of the same operation be wrongly rejected.

## 5. Nonce burning is a single `UPDATE ... WHERE used_at IS NULL`, not a check-then-set

```java
// AccessGrantRepository
@Modifying
@Query("UPDATE ConsentAccessGrantEntity g SET g.usedAt = :usedAt WHERE g.nonce = :nonce AND g.usedAt IS NULL")
int markUsedIfUnused(byte[] nonce, Instant usedAt);
```

`markUsed` in §4 calls this and checks the return value is `1`. If it's `0`, the nonce was
already burned by a concurrent request — two connector calls racing on the same grant
(a retried `FETCH` that got a slow-but-eventually-successful first response) — and the
second caller must treat that as `InvalidGrantException("nonce already used")`, not retry
the update. A separate `isNonceUsed` check followed by a separate `UPDATE` would have a
race window between the two statements; the single conditional `UPDATE` has none.

## 6. `AccessAuthority` — the composed decision

```java
package com.samanvay.consent.internal.service;

@Service
class DefaultAccessAuthority implements AccessAuthority {

    private final IdentityLinking identityLinking;     // identity.api
    private final ConsentRepository consents;
    private final ConsentRequestRepository consentRequests;
    private final DiscoveryRegistry registry;          // registry.api
    private final GrantSigner signer;
    private final AccessGrantRepository grants;
    private final AuditService audit;                  // audit.api

    @Override
    @Transactional
    public AccessDecision authorize(AccessRequest req) {
        if (identityLinking.activeLink(req.subject().citizenId(), req.departmentCode()).isEmpty())
            return deny(req, DenialReason.NO_ACTIVE_LINK, null);

        var consent = consents.find(req.requester(), req.subject(), req.category(), req.purpose());
        if (consent.isEmpty()) {
            var remedy = consentRequests.createFor(req);
            return deny(req, DenialReason.NO_CONSENT, remedy);
        }
        var c = consent.get();
        if (c.status() == ConsentStatus.REVOKED)            return deny(req, DenialReason.CONSENT_REVOKED, null);
        if (c.validUntil().isBefore(Instant.now()))         return deny(req, DenialReason.CONSENT_EXPIRED, null);
        if (frequencyExceeded(c))                           return deny(req, DenialReason.FREQUENCY_EXCEEDED, null);

        var pointer = registry.locate(req.subject(), req.departmentCode(), req.category(), req.requester());
        if (pointer.isEmpty())                              return deny(req, DenialReason.NO_POINTER, null);
        var p = pointer.get();
        if (p.validUntil().isBefore(Instant.now()))         return deny(req, DenialReason.POINTER_EXPIRED, null);
        if (!clearanceCovers(req.requester(), p.sensitivity())) return deny(req, DenialReason.INSUFFICIENT_CLEARANCE, null);
        if (p.freshness().isStale() && !journeyAcceptsStale(req)) return deny(req, DenialReason.STALE_NOT_ACCEPTED, null);

        var unsigned = new UnsignedGrant(UUID.randomUUID(), randomNonce(), c.id(), c.version(),
            req.subject(), req.requester(), req.category(), req.departmentCode(), req.connectorRef(),
            req.purpose(), Instant.now(), Instant.now().plusSeconds(60));
        var grant = new AccessGrant(unsigned, signer.sign(unsigned));

        grants.insert(grant);
        audit.record(grantIssuedEntry(req, grant));
        return new AccessDecision.Granted(grant);
    }

    private AccessDecision.Denied deny(AccessRequest req, DenialReason reason, ConsentRequest remedy) {
        audit.record(deniedEntry(req, reason));   // every denial is audited - see hld/05-consent.md §6
        return new AccessDecision.Denied(reason, Optional.ofNullable(remedy));
    }
}
```

## 7. Sequences

### 7.1 Citizen grants consent

```
Citizen (authenticated via Keycloak)
        │
        │ POST /api/consent/requests/{id}/grant
        ▼
ConsentController
        │  citizenAuthRef = jwt.getClaim("jti")   ← proves THIS session granted it
        ▼
ConsentService.grant(requestId, citizenId, authProof)
        │
        ├─▶ consent_request.status = GRANTED
        ├─▶ INSERT consent_artifact (version = 1, citizen_auth_ref = ...)
        ├─▶ INSERT consent_event (type = GRANTED)
        └─▶ publish ConsentGranted
```

### 7.2 Revocation (the mechanism, not the cross-module fan-out — see LLD.md §7.3 for that)

```
ConsentService.revoke(consentId, citizenId, reason)
        │
        │ UPDATE consent_artifact SET status = 'REVOKED', version = version + 1,
        │                              updated_at = now() WHERE id = ? AND subject_citizen_id = ?
        ▼
   rows updated == 0?  → ConsentNotFoundException (citizen doesn't own this consent, or it doesn't exist)
   rows updated == 1?  → INSERT consent_event (type = REVOKED) in the SAME transaction
        │
        ▼
   publish ConsentRevoked   ← Modulith persists this in the same transaction (see LLD.md §7.3)
```

The `version = version + 1` happens in the `UPDATE` statement itself (not
read-modify-write in Java), so two concurrent revoke attempts on the same row serialize
correctly at the database level with no application-level locking needed.

### 7.3 Phase 2: the consent record, catalog purposes and revocation (V186, V187)

**Purposes are catalog data.** `catalog_purpose` (V181, V184, V186) carries everything a
consent screen and a consent record need: `text`, `data_categories` (drive fetches),
`data_types` (the DEPA descriptors shown to the citizen), `requester_department`,
`requester_rule`, `max_duration_days`, `duration_rule`, `frequency`, `label_en` / `label_mr`
with a `MISSING|DRAFT|APPROVED` status each, and `separate_opt_in`. Java only checks that a
requested code exists and is `ACTIVE` (else `UnknownPurposeException`, 400) and interprets
`requester_rule`; there is no purpose enum, so a new journey adds purpose rows with no Java
change. The four `SCH_*` scholarship purposes are seeded by V186 next to the scholarship
journey seed (V21), because demo seeds are not yet separated from schema migrations.

**The record.** One purpose per consent. `consent_artifact` holds the citizen, the requester
(from the token or the catalog rule, never a request body), the purpose code,
`data_categories` and `data_types` copied from the catalog **at grant time**, `valid_from`
(created) and `valid_until` (expires: `min(365 days, max_duration_days)`), `status`
(`ACTIVE|REVOKED|EXPIRED`), `revoked_at` and `revoked_by` (token subject).

**Requester rules.**

| `requester_rule` | Requester |
|---|---|
| `CATALOG_DEPARTMENT` | `requester_department`; an officer/department client of any other department is refused (403, audited) |
| `PRIOR_AWARD_DEPARTMENT` | The department that approved the citizen's award: an `APPROVED` tracking application decided in the **academic year immediately before the current one** (Asia/Kolkata). The academic year's start month is scheme configuration, `catalog_journey.academic_year_start_month` (V188; scholarship = 6, June), read from the award's own journey; a scheme without it never yields a prior award. The deciding department is that journey's policy `requester`. No such award: `NoPriorAwardException` (409). Award decided by another department: `NotAwardingDepartmentException` (403). It must also be `requester_department`. |

`tracking` answers the award question through the `consent.api.ApprovedAwards` port (tracking
already depends on consent, so consent cannot call tracking). Separate opt-in and award-gated
purposes are never raised automatically as a fetch "remedy"; they are only asked for explicitly.

**Revocation.** `POST /api/consent/me/{id}/revoke` (citizen token; the citizen is the token's
bound record) or the older `POST /api/consent/{id}/revoke` (body `citizenId`). On both, a
consent that is not the signed-in citizen's own is a **404** (never 403), whatever the body says. Revoking sets `REVOKED`, `revoked_at`, `revoked_by`, bumps
the version (in-flight grants fail verification) and removes discovery. Revoking a consent that
is no longer active changes nothing. After that, `authorize()` under the consent returns
`Denied(CONSENT_REVOKED)` whose `message()` is *"You withdrew this permission, so this
department can no longer check this document."*; an expired consent returns
`Denied(CONSENT_EXPIRED)` with *"This permission ended on [date], so this department can no
longer check this document. If your application still needs it, you can give permission
again."* Both strings, and the status labels, live in one table,
`src/main/resources/consent/citizen-copy_en.properties` (checked by `ConsentCopyTableTest`
and the banned-phrase scan).

**Status at read time.** The citizen's permissions list (`GET /api/consent/citizens/{id}`)
works status out when it is read: an `ACTIVE` row past `valid_until` is reported as `EXPIRED`
with label "Ended", so an expired consent is never shown as Active. `REVOKED` reads
"Withdrawn by you".

**Audit** (same transaction, via `AuditService.record`): `CONSENT_GRANTED` and
`CONSENT_REVOKED` (actor = the token principal, `consent_id`, meta `purpose`, `principalType`,
`principalId`); `GRANT_DENIED` for refused fetches (reason, `consent_id`, meta `purpose`,
principal, plain `message`), in the caller's transaction (a denial is a return value, and the
caller may already hold the chain lock); `CONSENT_REQUEST_REFUSED` for refused requests of a
known purpose, written in its **own** transaction (REQUIRES_NEW through `AuditService.record`)
so it survives the request's rollback. **One audit row per refusal:** the refusal exception is
marked audited, `ApiExceptionHandler` sets `ApiAccessRefused.AUDITED_ATTRIBUTE`, and the 403
filter then skips its generic `API_FORBIDDEN` row. Every other 403 (e.g. a Spring Security
role denial) still gets exactly one `API_FORBIDDEN` row.

**Retention.** Consent records are kept for 7 years after they end (revoked or expired). This
is a platform policy, not a legal claim; no purge job exists yet.

**Follow-ups that block Phase 2 acceptance:** a domicile data category. (`frequency`
enforcement: §7.4.) **Deferred:** department scoping (Phase 2 step 9), the offline
verifier (Phase 2 PR 2), a job that marks rows `EXPIRED` (expiry is enforced at `authorize()`
and at read time from `valid_until`), and the retention purge job.

### 7.4 Phase 2: frequency enforcement, one check per document per application (V189, V190)

**Frequency values are closed.** `catalog_purpose.frequency` and `consent_artifact.frequency`
(the purpose's value, copied at grant time) may only be `ONCE`,
`ONCE_PER_DOCUMENT_PER_APPLICATION`, `ONCE_PER_PAYMENT` or `ONCE_PER_YEAR` (V190 CHECK
constraints `catalog_purpose_frequency_known`, `consent_artifact_frequency_known`; exactly the
V186 seed values). NULL stays allowed: legacy purposes and pre-V189 consents have none. Java maps
the value to `Purpose.Frequency`; `fromCode` throws on anything else, so a typo fails loudly at
load instead of switching the rule off.

**Which consents.** Enforced only for `ONCE` and `ONCE_PER_DOCUMENT_PER_APPLICATION`
(`Frequency.oneCheckPerApplication()`): one check of a document (data category) for an
application (journey instance id). **`ONCE_PER_PAYMENT` and `ONCE_PER_YEAR` are not yet
enforced** (pending Product's decision); they and NULL keep only the 20-per-24h
`frequency_limit`. A one-check consent asked for with no application id is refused
`APPLICATION_REQUIRED`.

**Table.** `consent_usage (id, consent_id, document_type, scope_key, grant_id, state
PENDING|USED, claimed_at, used_at, claim_token)` with `CONSTRAINT consent_usage_one_check UNIQUE
(consent_id, document_type, scope_key)`. `scope_key` (V190, renamed from `application_id`) holds
the application id for the two enforced values; no other scope is written yet.

**Flow** (`FetchDataDelegate` + `ConsentServices.authorize`):

1. `authorize()` is the outermost transaction (the delegate throws if called inside one). After
   the consent checks it claims with a fresh random `claim_token`:
   `INSERT ... ON CONFLICT ON CONSTRAINT consent_usage_one_check DO UPDATE SET grant_id,
   claimed_at, claim_token WHERE state = 'PENDING' AND claimed_at < clock_timestamp() - stale
   window RETURNING id`. No row back = refused: `Denied(CHECK_ALREADY_USED)` and exactly one
   `CONSENT_FREQUENCY_REFUSED` audit row. The claim comes before the registry lookup, i.e. before
   the transaction's first audit write. A later refusal in the same `authorize()` deletes the
   claim again. `Granted.claim()` carries `(id, token)`. The transaction commits before the
   connector is called.
2. The connector runs with no transaction open, under one total deadline (below).
3. `Success` marks the row USED with `UPDATE ... WHERE id = ? AND claim_token = ? AND state =
   'PENDING'`. Any other outcome deletes it with `DELETE ... WHERE id = ? AND claim_token = ? AND
   state = 'PENDING'` and writes one `CONSENT_CHECK_RELEASED` row (reason `TIMEOUT`,
   `REMOTE_FAULT`, `MALFORMED_RESPONSE`, ...). **0 rows = the claim was lost** (taken over after
   going stale, with a new token): the fetched result is thrown away (`Unavailable(CLAIM_LOST)`,
   never returned or stored) and exactly one `CONSENT_CHECK_LOST_CLAIM` row is written. The
   department has then been called twice, but only one result is kept.

**Stale window and total deadline.** Stale window = 2 x `samanvay.connector.timeout` (default
PT10S, which is also the connect timeout). `samanvay.connector.total-timeout` (default PT10S) is
ONE deadline over the whole exchange: every retry attempt, connect, send and the full body
(`ExchangeDeadline` opened by `ConnectorRuntimeImpl` around the retry-wrapped call; Retry does not
retry a deadline overrun). `DeadlineHttp` (JDK `HttpClient`, used by `RestAdapter`) buffers the
full body before its `sendAsync` future completes, waits on it with a timed `get` for the time
left, and on expiry calls `cancel(true)` on that same future, which aborts the exchange (JDK 16+).
It deliberately does not use `orTimeout` on that future (that would complete it first, and a later
`cancel` no longer reaches the transfer), nor a socket read timeout or `HttpRequest.timeout()`
(reset by each byte, or stop at the headers). Late bytes are discarded with the cancelled future.
**Boot check:** startup fails unless stale window > total-timeout (+ one `retry.wait` when
`retry.max-attempts` > 1: the only possible overshoot is a backoff that began just before the
deadline) + `samanvay.connector.stale-margin` (default PT5S).

**Refusal audits fail fast.** `RefusalAuditor` appends in its own transaction (REQUIRES_NEW). The
audit append marks its transaction (`AuditChainLock`, a transaction-bound synchronization); if the
calling transaction already holds the chain, `RefusalAuditor` throws at once instead of waiting
for ever on the advisory lock its suspended outer transaction holds.

**Officer copy.** The refusal message, *"This document has already been checked for this
application. The citizen's permission allows one check."*, lives in
`src/main/resources/consent/officer-copy_en.properties` (read by `ConsentCopy.officerDenied`),
checked by `ConsentCopyTableTest` and the banned-phrase scan of `src/main`.

**For the future disbursement PR (ONCE_PER_PAYMENT):** key the scope on a keyed HMAC of the
payment/instalment id, not the raw id, and decide how key rotation works: store the key version
next to the hash, or treat a rotation as a deliberate fresh start.

## 8. Error handling

| Exception (`consent.api`) | Raised when | HTTP mapping |
|---|---|---|
| `ConsentNotFoundException` | Revoke/grant targets a consent the caller doesn't own | 404 |
| `UnknownPurposeException` | Purpose code unknown or not `ACTIVE` (at request or grant time) | 400 |
| `RequesterNotEntitledException` | Requester is not the purpose's department | 403 (audited) |
| `NoPriorAwardException` | `PRIOR_AWARD_DEPARTMENT` purpose, no prior-year award | 409 (audited) |
| `NotAwardingDepartmentException` | Prior-year award was decided by another department | 403 (audited) |
| `InvalidGrantException` | Any `verifyOrThrow` failure (expired, reused nonce, bad signature, stale consent version) | Not exposed over HTTP — this is `connector`-internal; surfaces as `ConnectorResult.Unavailable` to the workflow, never as an API error to a citizen |
| `GrantSigningException` | Signing key unresolvable | 503 — see [hld/05-consent.md §7](../hld/05-consent.md#7-failure-modes): fail closed, no grants issued |

## 9. Events

| Event | Payload | Published when |
|---|---|---|
| `ConsentRequested` | `{requestId, citizenId, requesterId, categories, purposeText}` | A department triggers a consent request that has no matching active artifact |
| `ConsentGranted` | `{consentId, citizenId, requesterId, categories}` | §7.1 completes |
| `ConsentRevoked` | `{consentId, citizenId, version}` | §7.2 completes |
| `GrantIssued` / `GrantDenied` | `{requesterId, category, reason?}` | Every `authorize()` call, for the dashboard in HLD §10 |

## 10. Tests

| Test | Proves |
|---|---|
| `DefaultAccessAuthorityTest` (unit, mocked `identity`/`registry`/`consents`) | Each `DenialReason` triggers on its specific missing precondition; short-circuit order matches §1; a fully-satisfied request returns `Granted` with a signed grant |
| `Ed25519SigningRoundTripTest` | A grant signed by `GrantSigner` verifies under `Ed25519GrantVerifier`; a single flipped byte in any field fails verification |
| `GrantVerifierNeverHoldsPrivateKeyTest` | Reflective/static check: no field of `Ed25519GrantVerifier` is ever assigned a `PrivateKey` instance — a structural test for the property claimed in §4 |
| `NonceRaceIT extends PostgresIntegrationTest` | Two concurrent `markUsedIfUnused` calls on the same nonce: exactly one succeeds |
| `ConsentVersionRevocationIT extends PostgresIntegrationTest` | A grant issued at version 1, then the consent revoked (→ version 2): `verifyOrThrow` on the old grant now throws |
| `ConsentControllerIT` | `citizen_auth_ref` is captured from the actual authenticated session's token, not a client-supplied value |
| `ConsentFrequencyIT` (§7.4; real Postgres, real `AccessAuthority` and `ConsentUsage`, counting stub connector) | Two overlapping checks: one success, one `CHECK_ALREADY_USED`, one refusal row, one connector call; timeout / 5xx / unparseable / schema-invalid replies release the claim and the retry succeeds; a body trickled past the total limit is cut off and released; stale `PENDING` reclaimed (also under a race), fresh `PENDING` and `USED` not; `authorize()` runs outermost and its `GRANT_DENIED` survives a failing fetch path; a misspelled consent frequency is rejected; the `UNIQUE` constraint is in `pg_constraint` |
| `ConsentLostClaimIT` (stale window 2 s) | Worker A's claim is taken over by worker B while A's slow reply is pending: A's result is discarded, one `CONSENT_CHECK_LOST_CLAIM` row, the row ends USED with B's token (late success and late failure) |
| `DeadlineHttpTest`, `ResilienceDeadlineTest`, `ConnectorTimingCheckTest` | Total deadline cuts off a trickled body and cancels the exchange; retries share the deadline and an overrun is not retried; the boot check (good, boundary, retries push over) |
| `RefusalAuditorTest`, `AuditChainLockIT` | The audit append flags its transaction; a refusal audit from it throws at once |
| `PurposeFrequencyTest`, `PurposeCatalogColumnsIT` | Unknown frequency throws at load; a misspelled catalog frequency is rejected by the database |
