# Module: `identity`

| | |
|---|---|
| **Plane** | Control |
| **Owner** | B |
| **Phase** | 1 (linking) → 2 (resolution + review queue) |
| **Depends on** | `catalog`, `audit` |
| **Depended on by** | `consent` / `AccessAuthority`, `orchestration` |
| **HLD context** | [§5.3](../HLD.md#53-identity--linking-and-resolution) |

---

## 1. Purpose

Answer one question: **which departmental records belong to this human?**

Two distinct jobs, with opposite failure modes, deliberately named separately:

| | Job | Fails how |
|---|---|---|
| **Linking** | The citizen *proves* two departmental IDs are theirs | **Safe** — no link is created |
| **Resolution** | The system *proposes* that records may be one person | **Dangerous** — a wrong human is merged |

That asymmetry drives every decision in this module.

## 2. Responsibilities

### Owns

- The citizen master record and the citizen-asserted profile
- Department ID links, with provenance and confidence
- Blocking-key generation for candidate search
- The matching engine and its scoring
- The candidate review queue and its state machine

### Explicitly not responsible for

- **Authenticating the citizen.** Keycloak does that. This module consumes a verified
  subject.
- **Fetching department records.** It does not depend on `connector` — see §6.
- **Deciding whether an access is allowed.** It answers *"is there an active link?"*;
  `AccessAuthority` combines that with consent and sensitivity.
- **Storing department attribute data.** Only scores and feature vectors are retained.

## 3. Public interface — `com.samanvay.identity.api`

```java
public interface IdentityLinking {
    /** Citizen-asserted link, created after the citizen authenticates to the department IdP. */
    Link assertLink(UUID citizenId, String departmentCode,
                    LocalIdType type, String localId, AuthProof proof);

    Optional<Link> activeLink(UUID citizenId, String departmentCode);
    List<Link> activeLinks(UUID citizenId);
    void revokeLink(UUID linkId, String reason);
}

public interface IdentityResolution {
    /** Called by orchestration with a department record fetched under an admin grant. */
    CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord);

    Page<Candidate> reviewQueue(ReviewFilter f, Pageable p);   // IDENTITY_REVIEWER only

    /** The ONLY path from a probabilistic candidate to an active link. */
    Link confirm(UUID candidateId, String reviewerId, String note);
    void  reject(UUID candidateId, String reviewerId, String note);
}

public interface CitizenProfiles {
    UUID register(ProfileDraft draft);          // citizen-supplied at registration
    Profile profile(UUID citizenId);
}
```

## 4. Data owned

| Table | Notes |
|---|---|
| `identity_citizen` | Master record: UUID, status. Nothing else |
| `identity_profile` | Name variants (Latin + Devanagari), `dob` + `dob_precision`, gender, masked contact, `father_name` — **citizen-supplied, therefore consented by construction** |
| `identity_link` | citizen, department, `local_id_type`, `local_id_token`, `provenance`, `confidence`, `verified_at`, `status` |
| `identity_match_key` | Hashed blocking keys — `metaphone(family_name)+birth_year`, `pincode+dob` |
| `identity_candidate_match` | `score`, `features` jsonb, `status`, `reviewed_by`, `reviewed_at` |

`provenance` ∈ `CITIZEN_ASSERTED | DETERMINISTIC | PROBABILISTIC | OFFICER_CONFIRMED`.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `LinkAsserted` | `notifications`, `registry` (pointer discovery for the new department) |
| `LinkRevoked` | `consent` (dependent grants become unissuable) |
| `CandidateRaised` | `notifications` (reviewer queue alert) |
| `CandidateConfirmed` / `CandidateRejected` | `audit` context, `notifications` |

### Consumed

| Event | Reaction |
|---|---|
| `DepartmentRegistered` | Prepare blocking-key configuration for the new department |

## 6. Key decisions

### The safety invariant, enforced three times

> A `PROBABILISTIC` match never becomes an active link automatically, at any confidence
> score. Only citizen assertion or explicit officer confirmation creates one.

| Level | Mechanism |
|---|---|
| Service | `IdentityLinking` refuses the transition |
| Database | `CHECK (status <> 'ACTIVE' OR provenance <> 'PROBABILISTIC')` |
| Authorization | Only `IDENTITY_REVIEWER` may call `confirm()`, and it is audited |

Three levels because a service-layer bug must not be able to produce the dangerous state.
Auto-merging at 0.97 confidence means that at scale, two real people are eventually merged
in a benefits system — wrong bank account, denied scholarship, one person's caste
certificate attached to another. That is not a bug report.

### `identity` never calls `connector`

`connector` → `consent` → `identity`. A call in the other direction closes a cycle.

Resolution therefore works by **inversion**: `orchestration` fetches department records
under an admin-purpose grant and calls `submitCandidate(...)`. This is also correct under
**P2** — moving data is the data plane's job.

### The platform stores no department attributes

Matching compares fetched records against the **citizen-asserted profile**, transiently.
Only the score and the feature vector persist. The central store of personal data stays
small enough to describe in one sentence.

### `dob_precision` and dual-script names are load-bearing

A large share of legacy Indian government records carry only a birth year; an exact-date
rule silently fails against them. Maharashtra records arrive in both Devanagari and Latin;
treating `रमेश` and `Ramesh` as different people is the most common source of duplicate
beneficiary records. Both cost nothing now and are expensive to retrofit.

### Deterministic matches may auto-link; only ambiguity queues

An exact match on a strong shared identifier is `DETERMINISTIC`, not `PROBABILISTIC`, and
may link automatically. This keeps the review queue proportional to genuine ambiguity
rather than to population — the mitigation for the reviewer-volume risk in
[§15](../HLD.md#15-risks).

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| Citizen fails department IdP authentication during linking | No link. Retry offered. Attempt audited |
| Two citizens claim the same department local ID | Second assertion rejected; both flagged for review; audited as a security event |
| Matching produces an implausible volume of candidates | Blocking keys are too loose. Alarm; queue is rate-limited, not silently truncated |
| Reviewer confirms a wrong link | Link is revocable; revocation is audited and cascades to dependent grants |
| Profile data is thin (name only) | Candidate generation still runs; scores are low and stay in the queue |

## 8. Acceptance criteria

- [ ] A citizen can assert a link only after authenticating to that department's IdP
- [ ] `INSERT` of an `ACTIVE` + `PROBABILISTIC` link **fails at the database**
- [ ] Only `IDENTITY_REVIEWER` can confirm a candidate; the confirmation is audited with the score
- [ ] Deterministic exact-ID matches auto-link; everything else queues
- [ ] Matching handles a Devanagari/Latin name pair as the same person
- [ ] Matching handles a `YEAR`-precision DOB without falsely rejecting
- [ ] No department attribute values are found in any `identity_*` table after a resolution scan
- [ ] `identity` has no compile-time dependency on `connector` (Modulith verifies)

## 9. Open questions for LLD

- Scoring algorithm and feature set: which comparators (Jaro-Winkler on transliterated
  names, date proximity under precision, address token overlap) and what weights.
- Transliteration approach for Devanagari↔Latin — library choice, and whether comparison
  happens in a normalised third form.
- Confidence bands: what auto-rejects as noise, what queues, and whether the queue is
  ordered by score or by application urgency.
- Whether `identity_match_key` needs periodic regeneration when a profile is edited.
