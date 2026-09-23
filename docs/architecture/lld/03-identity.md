# LLD: `identity`

| | |
|---|---|
| **HLD charter** | [hld/03-identity.md](../hld/03-identity.md) |
| **Migration range** | V40–V59 |
| **Package** | `com.samanvay.identity` |
| **Depends on** | `catalog.api`, `audit.api` |

---

## 1. Migration: `V40__identity_init.sql`

```sql
CREATE TABLE identity_citizen (
    id          UUID PRIMARY KEY,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MERGED','SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_profile (
    citizen_id      UUID PRIMARY KEY REFERENCES identity_citizen(id),
    name_latin      VARCHAR(200) NOT NULL,
    name_devanagari VARCHAR(200),
    given_name      VARCHAR(100),
    family_name     VARCHAR(100),
    father_name     VARCHAR(200),
    dob             DATE NOT NULL,
    dob_precision   VARCHAR(10) NOT NULL CHECK (dob_precision IN ('DAY','MONTH','YEAR')),
    gender          VARCHAR(10),
    contact_masked  VARCHAR(50),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_link (
    id               UUID PRIMARY KEY,
    citizen_id       UUID NOT NULL REFERENCES identity_citizen(id),
    department_code  VARCHAR(60)  NOT NULL,
    local_id_type    VARCHAR(60)  NOT NULL,
    local_id_token   VARCHAR(200) NOT NULL,
    provenance       VARCHAR(30)  NOT NULL CHECK (provenance IN
        ('CITIZEN_ASSERTED','DETERMINISTIC','PROBABILISTIC','OFFICER_CONFIRMED')),
    confidence       NUMERIC(4,3),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    verified_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Level 2 of the three-level invariant (§4). A service bug cannot
    -- produce this row no matter what Java code does.
    CONSTRAINT chk_no_auto_probabilistic_link
        CHECK (NOT (status = 'ACTIVE' AND provenance = 'PROBABILISTIC')),

    -- Also closes a named HLD failure mode: two citizens cannot both
    -- claim the same department-issued identifier.
    UNIQUE (department_code, local_id_type, local_id_token)
);

CREATE TABLE identity_match_key (
    id          UUID PRIMARY KEY,
    citizen_id  UUID NOT NULL REFERENCES identity_citizen(id),
    key_type    VARCHAR(30) NOT NULL,   -- 'NAME_YEAR' | 'PINCODE_DOB'
    key_value   VARCHAR(200) NOT NULL,  -- hashed, not raw - see §3
    UNIQUE (key_type, key_value, citizen_id)
);
CREATE INDEX idx_match_key_lookup ON identity_match_key (key_type, key_value);

CREATE TABLE identity_candidate_match (
    id               UUID PRIMARY KEY,
    citizen_id       UUID NOT NULL REFERENCES identity_citizen(id),
    department_code  VARCHAR(60) NOT NULL,
    score            NUMERIC(4,3) NOT NULL,
    features         JSONB NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','REJECTED')),
    reviewed_by      VARCHAR(100),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (citizen_id, department_code)
);
```

## 2. The three-level invariant, shown together

> A `PROBABILISTIC` match never becomes an active link automatically, at any confidence
> score.

| Level | Where | What it stops |
|---|---|---|
| 1. Service | `IdentityResolutionService` (§4) never calls the link-insert path from the scoring path — there is no method that takes a score and produces an active link | A correct-looking refactor that "simplifies" the two paths into one |
| 2. Database | `chk_no_auto_probabilistic_link` (§1) | A service-layer bug that *does* try |
| 3. Authorization | `confirm()` (§5) requires `IDENTITY_REVIEWER`; `submitCandidate()` requires no such role and structurally cannot create an `ACTIVE` row | Anyone without reviewer standing, even with API access |

## 3. Blocking keys are hashed, never raw

```java
package com.samanvay.identity.internal.service;

final class BlockingKeys {
    static String nameYear(String familyName, int birthYear) {
        return sha256Hex(metaphone(familyName) + ":" + birthYear);
    }
    static String pincodeDob(String pincode, LocalDate dob) {
        return sha256Hex(pincode + ":" + dob);
    }
}
```

Hashing the derived key (not the underlying name/pincode) means `identity_match_key` is
useless for anything except "does this new record share a blocking bucket with an
existing citizen" — it cannot be reversed into a name or address list, which matters
because this table exists purely as a search-space narrower, not a place anyone should
ever need to read a value out of.

## 4. Resolution: deterministic path first, then scored candidates

```java
package com.samanvay.identity.internal.service;

@Service
class IdentityResolutionService implements IdentityResolution {

    @Override
    @Transactional
    public CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord) {
        String localId = extractLocalId(departmentRecord);

        // Deterministic: an exact match on a strong identifier the citizen
        // already asserted. This is NOT the probabilistic path and may
        // link immediately - it never touches the scorer.
        var existingLink = links.findByLocalId(departmentCode, localId);
        if (existingLink.isPresent()) return CandidateRef.alreadyLinked(existingLink.get().citizenId());

        // Probabilistic: blocking keys narrow the search before scoring -
        // never compare against the whole citizen population.
        var keys = deriveBlockingKeys(departmentRecord);
        for (UUID citizenId : matchKeys.findCitizensByAnyKey(keys)) {
            double score = scorer.score(profiles.get(citizenId), departmentRecord);
            if (score < NOISE_FLOOR) continue;                     // 0.60 - discarded, never queued
            var candidate = candidateMatches.upsert(citizenId, departmentCode, score, features(departmentRecord));
            if (score >= HIGH_CONFIDENCE)                          // 0.90 - still queued, just prioritized
                events.publishEvent(new CandidateRaised(candidate.id(), Priority.HIGH));
        }
        return CandidateRef.queued();
    }

    @Override
    @PreAuthorize("hasRole('IDENTITY_REVIEWER')")
    @Transactional
    public Link confirm(UUID candidateId, String reviewerId, String note) {
        var candidate = candidateMatches.findById(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));
        candidateMatches.updateStatus(candidateId, MatchStatus.CONFIRMED, reviewerId, note);
        var link = links.insert(candidate.citizenId(), candidate.departmentCode(), Provenance.OFFICER_CONFIRMED, candidate.score(), LinkStatus.ACTIVE);
        audit.record(candidateConfirmedEntry(candidateId, reviewerId, candidate.score()));
        return link;
    }
}
```

### Scoring — a weighted linear model, deliberately not a trained classifier

```java
package com.samanvay.identity.internal.service;

@Component
class CandidateScorer {

    double score(Profile profile, JsonNode record) {
        double name   = jaroWinkler(profile.nameLatin(), record.path("name").asText());
        double dob    = compareDob(profile.dob(), profile.dobPrecision(), record);
        double father = profile.fatherName() != null
            ? jaroWinkler(profile.fatherName(), record.path("fatherName").asText(""))
            : 0.0;
        double address = tokenOverlap(profile.addressTokens(), record.path("address").asText(""));

        return 0.4 * name + 0.3 * dob + 0.2 * father + 0.1 * address;
    }

    private double compareDob(LocalDate dob, DobPrecision precision, JsonNode record) {
        var recordDob = FlexibleDateParser.parse(record.path("dob").asText());
        if (recordDob == null) return 0.0;
        return switch (precision) {
            case DAY   -> dob.equals(recordDob) ? 1.0 : 0.0;
            case MONTH -> sameYearMonth(dob, recordDob) ? 1.0 : 0.0;
            case YEAR  -> dob.getYear() == recordDob.getYear() ? 1.0 : 0.0;
        };
    }
}
```

**Confidence bands, resolving the HLD's open question:**

| Score | Treatment |
|---|---|
| `< 0.60` | Discarded — not queued. Queuing pure noise trains reviewers to stop reading the queue carefully |
| `0.60 – 0.89` | Queued, normal priority |
| `≥ 0.90` | Queued, **still not auto-linked** (§2), but flagged high-priority for fast bulk review |

A weighted linear model over four comparators, not a trained classifier: there's no
labeled training data at hackathon scale, the comparators are individually explainable
(a reviewer can see *why* a candidate scored 0.82 — name 0.9, dob 1.0, father 0.6, address
0.3 — which a black-box model wouldn't give them), and 40/30/20/10 is a defensible,
documented starting point that can be tuned once real data shows which comparator
actually predicts a correct match best.

## 5. Sequences

### 5.1 Citizen-asserted linking

```
GET  /api/identity/citizens/{id}/connect-accounts?journeyCode=…
        │  missing departments only (one row per department, not per document)
        │  providers from GET /api/identity/proof-providers (LinkProofProvider SPI)
        │
Citizen presents a typed proof from a labeled LinkProofProvider
        (DigiLocker sandbox mock or Local ID + OTP demo; not Keycloak SSO)
        │
        │ POST /api/identity/links  { departmentCode, localIdType, localId, provider, proof }
        ▼
IdentityLinking.assertLink(...)
        │
        ├─▶ LinkProofProvider.verify(...) — missing/invalid proof is rejected
        ├─▶ skip if this citizen already has an ACTIVE link to the department
        ├─▶ INSERT identity_link (provenance = CITIZEN_ASSERTED, status = ACTIVE)
        │      — UNIQUE(department_code, local_id_type, local_id_token) rejects
        │        a second citizen asserting the same local id
        └─▶ publish LinkAsserted
```

Journey start (`POST /api/journeys/{code}/start`) refuses with `MISSING_DEPARTMENT_LINKS`
until every distinct source department in the journey policy has an active link.

### 5.2 Officer review queue (bulk-confirm path for high-confidence clusters)

```
IDENTITY_REVIEWER
        │  GET /api/identity/review-queue?priority=HIGH
        ▼
reviewQueue(filter, page)  → sorted by score desc within priority
        │
        │  POST /api/identity/candidates/{id}/confirm   (one at a time,
        │                                                  §4's confirm())
        ▼
   Link created, provenance = OFFICER_CONFIRMED, audited with the score
```

## 6. Error handling

| Exception (`identity.api`) | Raised when | HTTP |
|---|---|---|
| `DuplicateLocalIdException` | `assertLink` on a local id already claimed by another citizen | 409 — surfaced to both parties as a security event, not a generic conflict |
| `CandidateNotFoundException` | `confirm`/`reject` on an unknown candidate id | 404 |
| `LinkProofInvalidException` | The department IdP's assertion doesn't verify | 401 |

## 7. Events

| Event | Payload |
|---|---|
| `LinkAsserted` | `{citizenId, departmentCode}` |
| `LinkRevoked` | `{citizenId, departmentCode, reason}` |
| `CandidateRaised` | `{candidateId, priority}` |
| `CandidateConfirmed` / `CandidateRejected` | `{candidateId, reviewerId}` |

## 8. Tests

| Test | Proves |
|---|---|
| `NoAutoLinkConstraintIT extends PostgresIntegrationTest` | A raw `INSERT ... (status='ACTIVE', provenance='PROBABILISTIC')` fails at the database, bypassing the service entirely |
| `DeterministicVsProbabilisticTest` | An exact local-id match never reaches the scorer; a fuzzy-only match never auto-links regardless of score |
| `CandidateScorerTest` | A `YEAR`-precision DOB doesn't falsely reject a same-year, different-day match; a Devanagari/Latin name pair scores as a strong match |
| `NoiseFloorTest` | A score of 0.55 produces no `identity_candidate_match` row at all |
| `ConfirmRequiresReviewerRoleTest` | `confirm()` called without `IDENTITY_REVIEWER` is rejected before touching any row |
| `ConnectAccountsIT` | Scholarship checklist is 3 departments not 4 documents; already-linked skipped; links via #16 LinkProofProvider SPI |
| `IdentityProofProvidersIT` | GET /api/identity/proof-providers lists DigiLocker sandbox + OTP demo; typed POST /links; skip already-linked |
