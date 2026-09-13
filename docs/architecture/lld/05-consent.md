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

## 8. Error handling

| Exception (`consent.api`) | Raised when | HTTP mapping |
|---|---|---|
| `ConsentNotFoundException` | Revoke/grant targets a consent the caller doesn't own | 404 |
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
