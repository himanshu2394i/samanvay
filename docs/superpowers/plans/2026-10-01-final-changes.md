# FINAL-CHANGES Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline). Steps use checkbox syntax.
> Source of truth for the design: `docs/FINAL-CHANGES.md` (section numbers below refer to it).

> **STATUS (2026-10-01): executed.** All 16 tasks were carried out in this session on branch `feat/dept-revenue` (uncommitted).
> The checkboxes below were not ticked one by one; the outcome of each task, the tests that prove it, and what was deliberately
> deferred are recorded in `docs/FINAL-CHANGES.md` section 15 and the "Deferred" list at its end. Differences from this plan:
> Task 8's compose/Dockerfiles were verified for real (Revenue image built and run, Agriculture SQL on a throwaway Postgres);
> Task 13 also covered the central schema (Task 14) because onboarding needs it; Task 15 stops short of Keycloak realm changes
> (the generator and committed realm JSON have drifted); Task 16 replaced "retire the sandbox" with a cutover runbook.

**Goal:** Build everything in FINAL-CHANGES.md: four separate department services, manifest v2, secure protocol
adapters, `resolve`, per-department login assertions + linking, one-go onboarding, seeded central schema.

**Architecture:** Each department is a standalone Spring Boot app under `departments/<name>/` (own data, own
manifest, own security, own login), reaching Samanvay core only over HTTP. Core learns each department from its
manifest and applies the declared protocol/security. Core gains: manifest v2 parsing, a multi-parameter credential
model, adapters that apply it, an optional `resolve` step, an assertion-based link proof, onboarding from manifest v2.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + AssertJ, ArchUnit, Docker Compose (Postgres, atmoz/sftp),
Flyway, Jackson 3 (`tools.jackson`).

## Global Constraints

- Branch `feat/dept-revenue` (rename not needed); **no commits, no PR** until the user says so (CLAUDE.md, user rule).
- Test first for every behaviour: write test, run, see the expected failure, implement minimally, run, run existing tests.
- A department never depends on `com.samanvay..` (ArchUnit rule per department).
- Manifest carries capability metadata only: no secrets, no citizen values, no SQL.
- Never store a raw Aadhaar number. Fake data only. Dev secrets only as env-overridable defaults.
- After every task: update `docs/FINAL-CHANGES.md` §15 build log + tick the box here.
- Ports: core 8080, simulators 8090, revenue 8091, dbt 8092, education 8093, agriculture 8094, Keycloak 8180.

---

## Part A - Department services

### Task 1: Revenue SFTP face (7/12 land record) - §1, §10
**Files:** Create `departments/revenue/sftp/712.csv`; Modify `RevenueManifestController.java`, `RevenueDepartmentTest.java`; Modify `docker-compose.yml` (service `revenue-sftp`).
**Produces:** manifest document `LAND_RECORD_7_12`, protocol `SFTP_CSV`, `access.SFTP {host,port,directory,fileNamePattern,format,columns,hostKeyFingerprint}`, `auth {SSH_KEY|PASSWORD}`; CSV keyed by `personId`.
- [ ] Test: manifest has 4 documents; the 7/12 one declares SFTP access with columns; CSV header equals declared columns and `RV-1001` has a row.
- [ ] Implement CSV + manifest block + compose service. Run tests.

### Task 2: Revenue IP allow-list - §13
**Files:** Modify `ApiKeyFilter.java` (+ config `revenue.allowed-ips`), test.
- [ ] Test: with allow-list `10.9.9.9` a local call is refused 403 even with a good key; empty list = allow all.
- [ ] Implement.

### Task 3: DBT service (REST + OAuth2 client credentials) - §1, §13
**Files:** Create `departments/dbt/**` (pom, app, records, token endpoint, bank endpoint, manifest, tests, boundary test).
**Produces:** `POST /oauth/token` (client_credentials -> bearer, expiring), `GET /v1/bank?dbtId=` (bearer required), manifest `auth.scheme=OAUTH2_CLIENT {tokenUrl, scopes}`; no `resolve` (document key = person ID); `identity.personIdType=DBT_ID`.
- [ ] Tests: no/invalid/expired token -> 401; valid -> bank JSON; manifest shape. Implement.

### Task 4: Education service (SOAP + WS-Security UsernameToken) - §1, §13
**Files:** Create `departments/education/**`.
**Produces:** `POST /marks/service` SOAP 1.1; request must carry a WS-Security UsernameToken, else a SOAP Fault; manifest `access.SOAP {endpoint,soapAction,requestTemplate}`, `auth.scheme=WS_SECURITY_USERNAME`; `identity.personIdType=EDU_STUDENT_ID`.
- [ ] Tests: missing/wrong token -> Fault; valid -> GetMarksResponse. Implement.

### Task 5: Agriculture service (JDBC + SFTP) - §1, §10, §13
**Files:** Create `departments/agriculture/**` (manifest/login app), `departments/agriculture/db/init.sql` (read-only view `v_farmer_record`, read-only role), `departments/agriculture/sftp/crop.csv`; Modify compose (own Postgres on 5434 + sftp).
**Produces:** manifest `access.JDBC {host,port,db,readOnlyView,keyColumn}`, SFTP access, `auth DB_USER` + `SSH_KEY/PASSWORD` w/ pinned host key.
- [ ] Tests: manifest shape; init.sql creates view + read-only role (checked by a Testcontainers-free SQL parse test or compose smoke); implement.

### Task 6: Journeys in each manifest - §2, §4
**Files:** Each department manifest controller + test.
- [ ] Each department publishes >=1 journey with `requiredCategories` across departments (e.g. scholarship needs INCOME + BANK + MARKS). Tests assert journeys + required categories. Implement.

### Task 7: Login assertion contract + per-department login - §8, §11
**Files:** Create `docs/contracts/login-assertion.md`; in each department `LoginController` (user ID + password demo users) + `AssertionSigner` (ES256/RS256 JWT) + `GET /.well-known/jwks.json`; manifest `identity {personIdType, jwksUrl, loginUrl}`.
**Produces (contract):** claims `iss, aud=samanvay, sub=personId, dept_code, person_id_type, auth_time, exp(<=5min), jti`; signed; keys via JWKS.
- [ ] Tests per department: good login -> verifiable assertion; bad password -> 401; assertion expires; JWKS verifies it. Implement (shared code copied per department; no shared lib, to keep builds independent).

### Task 8: Compose + Dockerfiles for all departments - §1
- [ ] Dockerfile per department, compose services, README table; `docker compose config` validates.

## Part B - Core (Samanvay middle layer)

### Task 9: Manifest v2 in core - §10, §12, §13
**Files:** Modify `catalog/api/DepartmentManifest.java`, `CatalogServices.discover`, tests.
- [ ] Test: parse Revenue's real manifest JSON (fixture copied from the department) incl. lookup/auth/access/identity/etag. Implement.

### Task 10: Credential model + secured adapters - §9, §13 (fixes the unsafe gap)
**Files:** `connector/internal/source/SourceCredentials.java` (named params), `RestAdapter`, `SoapAdapter`, `SftpCsvClient`, `JdbcQueryClient`, tests.
- [ ] REST applies API key header / OAuth2 client-credentials token / extra headers; honours `method` + `inputsIn`; SOAP adds WS-Security token; SFTP key login + host-key pin verified; tests against the department services' behaviour (WireMock-style in-JVM server).

### Task 11: Optional `resolve` step - §12
**Files:** `connector/internal/service/ConnectorRuntimeImpl.java`, connector `inputs` spec.
- [ ] Test: with resolve, runtime calls resolve then document with the chosen key (latest); without, key = person ID. Implement.

### Task 12: Assertion link proof + explicit link key - §11, §12
**Files:** `identity/internal/proof/DepartmentAssertionLinkProofProvider.java`, `DefaultJourneyService` (pass explicit link key), tests.
- [ ] Verify signature via JWKS from the onboarded manifest, `aud`, `exp`, freshness, `dept_code`; replay (`jti`) refused; link row created. Implement.

### Task 13: One-go onboarding from manifest v2 - §3, §10
**Files:** `catalog/internal/service/CatalogServices.java`, `CatalogController`, SPA `OnboardingPage.tsx`.
- [ ] One data source per distinct protocol/host; connector `inputs` generated; credentials form from `auth.parameters`; outbound IP shown; manifest `etag` change flagged. Tests (service + SPA flow).

### Task 14: Seeded central schema - §3, decisions
**Files:** `src/main/resources/db/migration/V200__central_schema_departments.sql` (+ mappings).
- [ ] Derived from the four departments' documents; test that each onboarded document's fields map to it.

### Task 15: Journey onboarding / caller credential - §4
- [ ] Caller credential issued per department (Keycloak service account + `source:` scopes); journey shows connected via readiness; department portal journey calls Samanvay. Tests.

### Task 16: Retire SANDBOX wiring + docs + full verification - §14.7
- [ ] Remove shared SANDBOX simulator wiring where replaced; update README + HLD/LLD; run all department tests + core tests; final §15 log.
