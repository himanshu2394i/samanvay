# Module: `consent` (including `AccessAuthority`)

| | |
|---|---|
| **Plane** | Control |
| **Owner** | Technical lead |
| **Phase** | 1 |
| **Depends on** | `identity`, `registry`, `catalog`, `audit`, `SecretStore` |
| **Depended on by** | `connector` (grant type + verification), `orchestration` |
| **HLD context** | [§5.5](../HLD.md#55-consent-and-access-grants) · [§6.1](../HLD.md#61-consent-gated-fetch--the-spine) · [§6.3](../HLD.md#63-mid-flight-consent-revocation) |

---

## 1. Purpose

Two things, deliberately in one module because they are two halves of a single guarantee:

1. **`consent`** — long-lived citizen authorization: who may access what, for what purpose,
   until when, revocable at any time.
2. **`AccessAuthority`** — the single decision point that composes identity, consent and
   registry into a **signed, short-lived, single-use `AccessGrant`**.

This module is the enforcement point for **P2**. It is owned by the technical lead because
it is the one place where the platform's core security invariant lives.

## 2. Responsibilities

### Owns

- Consent artifact lifecycle: request, grant, verify, revoke, expire
- Consent versioning
- `AccessAuthority` — the composed authorization decision
- Access grant issuance, signing, TTL and nonce burning
- The grant signing keypair (private key from `SecretStore`; public key distributed to `connector`)

### Explicitly not responsible for

- **Authenticating the citizen.** Keycloak does that; the module records `citizen_auth_ref`.
- **Deciding whether a *role* may call an endpoint.** That is Spring Security at the edge.
  Consent answers the *purpose* question, not the *role* question.
- **Fetching data.** It authorizes; `connector` executes.
- **Storing what was fetched.** Nothing fetched is ever persisted anywhere.

## 3. Public interface — `com.samanvay.consent.api`

```java
public interface ConsentService {
    ConsentRequest request(ConsentRequestDraft draft);      // returns a handle the citizen acts on
    ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof);
    void revoke(UUID consentId, UUID citizenId, String reason);
    List<ConsentArtifact> forCitizen(UUID citizenId);
    Optional<ConsentArtifact> find(RequesterRef requester, SubjectRef subject,
                                   DataCategory category, PurposeCode purpose);
}

/** The P2 enforcement point. The only issuer of AccessGrants in the system. */
public interface AccessAuthority {
    AccessDecision authorize(AccessRequest request);
}

public sealed interface AccessDecision {
    record Granted(AccessGrant grant) implements AccessDecision {}
    record Denied(DenialReason reason, Optional<ConsentRequest> remedy) implements AccessDecision {}
}

public record AccessGrant(
    UUID   id,                    // audit joins here
    byte[] nonce,                 // single-use; burned on execution
    UUID   consentId, int consentVersion,
    SubjectRef subject, RequesterRef requester,
    DataCategory category, String departmentCode, String connectorRef,
    PurposeCode purpose,
    Instant issuedAt, Instant expiresAt,     // +60s
    byte[] signature
) {}

/** Held by `connector`. Verification only — it cannot mint. */
public interface AccessGrantVerifier {
    void verifyOrThrow(AccessGrant grant, DataCategory expectedCategory, String expectedConnectorRef);
}
```

`DenialReason` is an enum, not a string: `NO_ACTIVE_LINK`, `NO_CONSENT`, `CONSENT_EXPIRED`,
`CONSENT_REVOKED`, `FREQUENCY_EXCEEDED`, `NO_POINTER`, `POINTER_EXPIRED`,
`INSUFFICIENT_CLEARANCE`, `STALE_NOT_ACCEPTED`. Denials feed the dashboard in
[§10](../HLD.md#10-observability), so they must be countable.

## 4. Data owned

| Table | Notes |
|---|---|
| `consent_request` | Pending requests awaiting citizen action |
| `consent_artifact` | subject, requester, purpose, categories, granularity, validity, `frequency_limit`, status, `version`, `citizen_auth_ref` |
| `consent_event` | Immutable grant/revoke/expire log (mirrored to `audit`) |
| `access_grant` | `id`, `nonce` (unique), consent id + version, subject, requester, category, department, connector, `issued_at`, `expires_at`, `used_at`, `signature` |

Grants are metadata. Persisted for nonce enforcement and audit; archived after 90 days.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `ConsentRequested` | `notifications` (ask the citizen) |
| `ConsentGranted` | `notifications`, `registry` (sensitive pointers become discoverable) |
| `ConsentRevoked` | `orchestration` (steps → `AUTHORIZATION_WITHDRAWN`), `notifications` |
| `GrantIssued` / `GrantDenied` | Observability |

### Consumed

| Event | Reaction |
|---|---|
| `LinkRevoked` (from `identity`) | Dependent consents become unusable; future grants denied `NO_ACTIVE_LINK` |
| `PointerExpired` (from `registry`) | Future grants for that pointer denied |

## 6. Key decisions

### The invariant is structural, not procedural

> `ConnectorRuntime` takes an `AccessGrant` as a **required argument**. There is no
> overload without one.

A developer bypassing consent must forge a signature, not merely forget a check. A rule
enforced by convention is a rule the third person to join the project breaks, in a hurry,
before a demo.

### Grants are signed asymmetrically

The control plane signs with a private key from `SecretStore`. `connector` holds only the
**public** key: it can verify a grant, and cannot mint one. This matters because
`connector` is the component the restriction applies to — letting it hold the signing key
would make the restriction self-administered.

### `id` ≠ `nonce`

They answer different questions and have different lifecycles:

| | Answers |
|---|---|
| `id` | *Which authorization was issued?* — audit joins on this |
| `nonce` | *Has this capability already been used?* — single-use enforcement burns this |

Collapsing them permanently assumes one grant = one operation. See also
[`connector` §6](06-connector.md) on `submission_attempt`.

### 60-second TTL and single use

Grants cross a process boundary and are therefore replayable. TTL plus nonce plus
consent-version check closes it. The TTL is short enough that revocation is effectively
immediate and long enough to survive a slow department call.

### `citizen_auth_ref` turns a record into an artifact

Storing the Keycloak token identifier of the granting session converts *"a row says they
consented"* into *"we can prove this authenticated session granted it."* One column.

### `consent_version` is how revocation reaches in-flight work

A grant carrying version 3 fails verification the moment the artifact reaches version 4. No
cache invalidation, no distributed purge — a field comparison. This is why
[§6.3](../HLD.md#63-mid-flight-consent-revocation) can claim sub-60-second revocation
honestly.

### Denial carries a remedy

`Denied(NO_CONSENT, remedy)` includes the `ConsentRequest` the citizen can act on. A wall
that says only "no" produces a support ticket; a wall that says "ask them" produces a flow.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Signing key unavailable | **No grants issued.** Fail closed. Alarm |
| Nonce collision | Cryptographically improbable; unique constraint makes it an error, never a silent reuse |
| Clock skew between issue and verify | Small tolerance (seconds), documented, tested. Never unbounded |
| Consent revoked between issue and use | Verification fails on `consent_version`; step → `AUTHORIZATION_WITHDRAWN` |
| Grant used twice | Second use rejected — nonce burned. Audited as a security event |
| Frequency limit exceeded | `Denied(FREQUENCY_EXCEEDED)`; citizen may re-consent |

## 8. Acceptance criteria

- [ ] `ConnectorRuntime` cannot be invoked without an `AccessGrant` (no such method exists)
- [ ] A grant with a tampered field fails signature verification
- [ ] A grant used twice is rejected and the second attempt is audited
- [ ] A grant older than 60 seconds is rejected
- [ ] Revoking consent causes the *next* access to be denied within the TTL
- [ ] Every denial produces an enum reason, countable on the dashboard
- [ ] `connector` does not have access to the private signing key (verified by inspection and test)
- [ ] A denial for missing consent returns an actionable consent request
- [ ] No fetched payload appears in any `consent_*` table

## 9. Open questions for LLD

- Signature algorithm and grant encoding — Ed25519 over a canonical byte form, or a signed
  JWT. JWT is more familiar to reviewers; raw Ed25519 is smaller and has no algorithm
  confusion surface.
- Whether `RECURRING` consent needs a per-window counter table or can derive frequency from
  `access_grant` history.
- Grant archival: move to a cold table or delete. Deletion loses the ability to explain a
  historical access, so archival is likely correct.
- Whether `AccessAuthority` should evaluate all checks and return **all** failing reasons,
  or short-circuit on the first. All-reasons is better for the citizen UI; short-circuit
  leaks less to a hostile requester.
