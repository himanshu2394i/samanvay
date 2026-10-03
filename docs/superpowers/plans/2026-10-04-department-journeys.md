# Department journeys Implementation Plan

> **For agentic workers:** Implement task by task with the native (inline) method. Steps use checkbox syntax. Test first, see it fail for the
> expected reason, implement the smallest change, see it pass, then run the module's existing tests.

**Goal:** Every citizen journey runs on its department's own portal and uses Samanvay through a department-callable API; Samanvay has no citizen UI.

**Architecture:** Department service = login + portal BFF (shared `departments/kit`) + journey definitions (single source for manifest and screens). It calls
Samanvay `/api/department/**` with its own Keycloak client credentials. Consent is signed by the department with its pinned manifest key. Staff
console gains a per-journey status and log page. The citizen realm becomes optional and is switched off.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, Maven, Flyway, Nimbus JOSE, Testcontainers, ArchUnit; React 19 + Vite + vitest; Keycloak; Docker/Caddy.

**Spec:** `docs/superpowers/specs/2026-10-04-department-journeys-design.md` (read it first; section numbers below refer to it).

## Global Constraints

- No em or en dashes and no emojis in any UI text or generated HTML (the landing page tests assert this for static pages).
- Accessible: labelled inputs, visible focus, `lang` set, light and dark via `prefers-color-scheme`, works at 375px width.
- Dates and clocks come from the injected `Clock` on the Samanvay side; tests use `Clock.fixed`.
- Department code and Samanvay code must not import each other (ArchUnit rule stays green). Contract between them is HTTP + JWS only.
- Never log tokens, assertions, passwords, or consent statements; log reasons only.
- Every refusal of a department call on the consent or identity path is one generic message; the reason goes to the log.
- Existing test commands: Samanvay `./mvnw -q test -Dtest=<Class>`; departments `./mvnw -q -f departments/<d>/pom.xml test`; frontend `cd frontend && npm test -- --run`.
- Migrations after V205 are V206 (consent_evidence) and V207 (identity_assertion_use).
- Demo codes: citizen one-time code `123456`, staff authenticator `000000`.

## Review Focus

- Wrong-department call: Education's token calling `/api/department/**` for a Revenue-requested journey or acting as Revenue. Expect 403 (Task 2, 3, 4).
- Replayed consent statement or login assertion (same `jti`). Expect refusal the second time (Task 1, 3).
- Consent statement signed by a key that is not the pinned manifest key. Expect refusal (Task 3).
- Two logins of the same person at different home departments, in either order. Expect one citizen, not an error (Task 2).
- `/login` opened directly, and a `return_to` for another host. Expect portal sign-in, and a refusal for the foreign host (Task 8).

## File Structure

Samanvay (`src/main/java/com/samanvay/...`):
- `identity/internal/proof/DepartmentAssertionVerifier.java` (new): the claim checks extracted from the proof provider.
- `identity/internal/proof/DepartmentHomeLoginProof.java` (new): `DEPT_HOME_LOGIN` provider, jti replay via `AssertionUseStore`.
- `identity/internal/service/CitizenResolver.java` (new): resolve-or-create + merge on collision.
- `identity/internal/web/DepartmentIdentityController.java` (new): `/api/department/citizens/resolve`, `/links/start`, `/links`.
- `consent/internal/service/DepartmentConsentService.java` (new), `consent/internal/web/DepartmentConsentController.java` (new), `ConsentStatementVerifier.java` (new).
- `orchestration/internal/web/DepartmentJourneyController.java` (new): readiness, applications.
- `catalog/...` manifest `portalUrl` (modify planner, parser, `DiscoveredManifest`, plan, journey record).
- `ops/internal/web/JourneyStatusController.java` (new): `/api/ops/journeys/{code}`.
- `shared/security/*` (modify): optional citizen realm.
- Migrations `V206__consent_evidence.sql`, `V207__identity_assertion_use.sql`.

Departments:
- `departments/pom.xml` (new reactor), `departments/kit/**` (new module).
- Per department: `journeys.json`, `<D>Portal.java` config wiring, changes to `LoginController`, `AssertionSigner` (name/dob claims), manifest controller, `application.yml`, `static/portal/` (generated copy).

Front end (`frontend/`): `portal.html`, `src/portal/**` (new), `src/pages/JourneyStatusPage.tsx` (new), router + nav wiring, removal of citizen routes.

Scripts and deploy: `scripts/build-portal.sh`, `scripts/provision-department-clients.py`, changes to `scripts/gen-demo-data.py`, deploy compose/env, `docs/FINAL-CHANGES.md` phase 8.

---

### Task 1: Shared assertion verifier, `DEPT_HOME_LOGIN` proof, jti replay table

**Files:**
- Create: `src/main/java/com/samanvay/identity/internal/proof/DepartmentAssertionVerifier.java`, `DepartmentHomeLoginProof.java`, `AssertionUseStore.java`, `JdbcAssertionUseStore.java`
- Modify: `DepartmentAssertionLinkProofProvider.java` (delegate to the verifier, behaviour unchanged), `identity/api/LinkProofKind.java` (add `DEPT_HOME_LOGIN`)
- Create: `src/main/resources/db/migration/V207__identity_assertion_use.sql`
- Test: `src/test/java/com/samanvay/identity/internal/proof/DepartmentHomeLoginProofTest.java` (copy the fixtures of the existing `DepartmentAssertionLinkProofProviderTest` for key and JWS building)

**Interfaces:**
- Produces: `record VerifiedAssertion(String departmentCode, String personType, String personId, String jti, String state, String nonce, String name, LocalDate dob)`
  and `VerifiedAssertion DepartmentAssertionVerifier.verify(String token, String departmentCode)` (throws `LinkProofInvalidException`; checks signature, issuer, audience, dept_code, person_id_type, lifetime, auth_time; does NOT check state or jti).
- Produces: `boolean AssertionUseStore.firstUse(String departmentCode, String jti, Instant expiresAt)`.
- Produces: `VerifiedAssertion DepartmentHomeLoginProof.verify(String token, String departmentCode)` = verifier + `firstUse` check (refuses a replay).
- `V207`: `CREATE TABLE identity_assertion_use (department_code text not null, jti text not null, used_at timestamptz not null, expires_at timestamptz not null, PRIMARY KEY (department_code, jti))`.

- [ ] **Step 1: Write failing tests** in `DepartmentHomeLoginProofTest`: (a) a valid assertion with no state in the store verifies and returns the person ID and the `name`/`dob` claims;
  (b) the same token a second time is refused; (c) wrong audience, wrong department, HS256 token, expired token, `auth_time` older than max age are each refused; (d) the refusal is the single `LinkProofInvalidException`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=DepartmentHomeLoginProofTest` — expect compile failure (classes missing).
- [ ] **Step 3:** Extract the checks (lines 100-137 and helpers of `DepartmentAssertionLinkProofProvider`) into `DepartmentAssertionVerifier`; make the existing provider call it, then consume state/nonce as today. Add the provider, store and migration.
- [ ] **Step 4:** Run the new test and the existing `DepartmentAssertionLinkProofProviderTest`, `IdentityLinkingIT`-style tests (`./mvnw -q test -Dtest='Department*Proof*,Identity*'`). All pass.
- [ ] **Step 5:** Commit `feat(identity): shared assertion verifier and home-login proof with jti replay guard`.

### Task 2: Resolve or create the citizen, merge on proven collision, department identity API

**Files:**
- Create: `identity/internal/service/CitizenResolver.java`, `identity/internal/web/DepartmentIdentityController.java`, `identity/api/CitizenResolution.java`
- Modify: `IdentityServices.java` (expose link move + status `MERGED`; read it first), `shared/security/SecurityConfig.java` (route `/api/department/**` to role DEPARTMENT)
- Test: `identity/internal/service/CitizenResolverTest.java` (Postgres via the existing Testcontainers base class), `identity/internal/web/DepartmentIdentityControllerTest.java`

**Interfaces:**
- Consumes: `DepartmentHomeLoginProof.verify` (Task 1), `Caller.department()`.
- Produces: `record CitizenResolution(UUID citizenId, boolean created)`; `CitizenResolution CitizenResolver.resolve(VerifiedAssertion a)`;
  `UUID CitizenResolver.linkOrMerge(UUID citizenId, String departmentCode, String personType, String personId)` returns the surviving citizen ID.
- HTTP: `POST /api/department/citizens/resolve {assertion}` -> `{citizenId, created}`; `POST /api/department/links/start {citizenId, departmentCode, returnTo}` -> `{loginUrl}`;
  `POST /api/department/links {citizenId, assertion}` -> `{citizenId}` (surviving). The token's department must equal the assertion's department for resolve.

- [ ] **Step 1: Write failing tests:** resolve twice with the same person returns the same citizen (`created` false the 2nd time); resolve creates a profile from `name`/`dob` (dob absent uses `1900-01-01` and profile flagged unverified: check the column; if none, store it and document in the test name);
  Education home login, then Agriculture home login, then Agriculture links Education with the same Education person: the Agriculture citizen merges into the Education one and `linkOrMerge` returns the Education citizen, old row `MERGED`, audit `CITIZEN_MERGED`;
  merge refused when the implicit citizen already has a consent or a journey instance; Education token calling resolve with a Revenue assertion gets 403; no token gets 401; role CITIZEN gets 403.
- [ ] **Step 2:** Run them; expect failures for missing classes.
- [ ] **Step 3:** Implement. `linkOrMerge`: try to insert the link; on the unique-violation find the owner; if owner != citizen and the citizen is "implicit and empty" (created by `resolve`, no consent, no instance, links only home-department ones) move all of that citizen's links to the owner, set status `MERGED`, audit; else throw the existing duplicate error.
- [ ] **Step 4:** Run the new tests plus `./mvnw -q test -Dtest='Identity*,SecurityMatrix*'`.
- [ ] **Step 5:** Commit `feat(identity): department-callable citizen resolve, links and merge-on-collision`.

### Task 3: Signed consent statement, evidence, department consent API

**Files:**
- Create: `V206__consent_evidence.sql`, `consent/internal/service/ConsentStatementVerifier.java`, `DepartmentConsentService.java`, `consent/internal/web/DepartmentConsentController.java`, `consent/internal/repository/ConsentEvidenceRepository.java`
- Modify: `ConsentServices.java` only if `grant` needs a proof-reference overload (look at its `AuthProof` parameter first)
- Test: `consent/internal/service/ConsentStatementVerifierTest.java`, `consent/internal/web/DepartmentConsentControllerTest.java`, `ConsentEvidenceIT`

**Interfaces:**
- Consumes: `DepartmentCatalog` pinned thumbprint and `identity` nothing; `ConsentService.request/grant`.
- Produces: `V206 consent_evidence(consent_id uuid pk references consent_artifact, statement text not null, jti text not null unique, department_code text not null, key_thumbprint text not null, created_at timestamptz not null)`.
- HTTP: `POST /api/department/consents/requests {citizenId, journeyCode}` -> `{requestId, purposeText, categories[], providers[], validUntil, nonce, expiresAt}`;
  `POST /api/department/consents {statement}` -> `ConsentArtifact`. Nonce is stored with the open request (add column `nonce` to the request table in V206 or a side table `consent_request_nonce`).
- Statement is the JWS of spec 3.4.

- [ ] **Step 1: Failing tests** (build statements with a test ES256 key and pin its thumbprint in a fake catalog): valid statement grants and stores evidence; second submission (same `jti`) refused; signature from a different key refused; wrong `dept_code` or caller department refused; nonce mismatch / reused refused; request belongs to another citizen refused; expired `exp` refused; categories not equal to the request's refused; all refusals identical body.
- [ ] **Step 2:** Run; expect missing classes.
- [ ] **Step 3:** Implement per spec 4.2; thumbprint compares to the department's pinned manifest key via the catalog API (add an accessor to `DepartmentCatalog` if missing).
- [ ] **Step 4:** Run the new tests plus `./mvnw -q test -Dtest='Consent*'`.
- [ ] **Step 5:** Commit `feat(consent): department-signed consent statements with stored evidence`.

### Task 4: Journey start scoping, readiness and department application reads

**Files:**
- Create: `orchestration/internal/web/DepartmentJourneyController.java`
- Modify: `orchestration/internal/web/JourneyController.java` (start guard), tracking read services as needed (department-scoped lookups)
- Test: `DepartmentJourneyControllerTest`, extend the existing journey start test

**Interfaces:**
- Produces: `GET /api/department/journeys/{code}/readiness?citizenId=` -> `{journey, requester, items:[{category, department, connected, consentActive}]}`;
  `GET /api/department/applications?citizenId=`, `/{ref}`, `/{ref}/steps`, `/{ref}/issued-records`.
- Start: `POST /api/journeys/{code}/start` by a department caller is allowed iff journey requester == caller department.

- [ ] **Step 1: Failing tests:** Education client starts the scholarship (needs Revenue + DBT scopes it does not have) succeeds with consent; Revenue client starting the scholarship gets 403; applications of another department's journey are 404 for the caller; readiness reports `connected=false` until a link exists and `consentActive` after the grant.
- [ ] **Step 2-4:** Run to fail, implement, run `./mvnw -q test -Dtest='Journey*,Tracking*,Orchestration*'`.
- [ ] **Step 5:** Commit `feat(orchestration): department-scoped journey start, readiness and application reads`.

### Task 5: Manifest `portalUrl` and citizen realm optional

**Files:**
- Modify: `catalog/internal/service/ManifestOnboardingPlanner.java`, `ManifestSignatures`/parser, `catalog/api/DiscoveredManifest.java`, `OnboardingPlan` (journeys get `portalUrl`), same-host rule, journey storage if journeys are persisted
- Modify: `shared/security/SecurityRealmsProperties.java`, `SecurityConfig.java`, `RealmIssuerStartupCheck.java`, `AuthConfigController.java`, `application*.yml`
- Test: planner test (portalUrl on another host refused), `AuthConfigControllerTest` (no citizen issuer: only staff listed), startup-check test

- [ ] **Step 1: Failing tests:** manifest with `portalUrl` on the manifest's host appears in the plan; `portalUrl` on a different host fails the plan; with `samanvay.security.citizen.issuer-uri` unset the app starts, `/ui/auth-config` lists only staff, and a citizen-role endpoint answers 401 for any token.
- [ ] **Step 2-4:** Fail, implement, run `./mvnw -q test -Dtest='Manifest*,Onboard*,AuthConfig*,Realm*,Security*'`.
- [ ] **Step 5:** Commit `feat: manifest portalUrl; citizen realm is optional`.

### Task 6: Remove the middle-layer citizen portals and UI

**Files:**
- Delete: `src/main/resources/static/{scholarship,farmer,licence}` and the tests that only cover them (`ScholarshipPortal*IT`, etc.: find with `grep -rl "static/scholarship\|/scholarship/" src/test`); the citizen React routes/pages/tests
- Create: `src/main/resources/static/index.html` short staff landing page (title, one sentence, link to `/staff/`, no dash characters)
- Modify: `frontend/src/main.tsx` and router (no redirect hack needed once citizen routes are gone)

- [ ] **Step 1:** List what will be deleted and which tests reference it; record the list in the commit message.
- [ ] **Step 2:** Write `LandingTest` for the new `/` page (200, mentions staff, no `<script`), run, see it fail.
- [ ] **Step 3:** Delete files, add the page.
- [ ] **Step 4:** Run `./mvnw -q test` (full) and `cd frontend && npm test -- --run && npm run build`. Fix leftovers.
- [ ] **Step 5:** Commit `chore: remove citizen portals and citizen UI from the middle layer`.

### Task 7: `departments/kit` module and fake-Samanvay harness

**Files:**
- Create: `departments/pom.xml` (packaging pom, modules `kit`, four departments; parent/BOM same as the root), `departments/kit/pom.xml`, `kit/src/main/java/in/samanvay/departments/kit/{SamanvayClient,ConsentSigner,PortalSession,JourneyCatalog,PortalController,PortalProperties}.java`
- Modify: each department `pom.xml` (parent + kit dependency), Dockerfiles (context `departments/`)
- Test: `kit/src/test/java/.../{SamanvayClientTest,ConsentSignerTest,PortalSessionTest,FakeSamanvay}.java`

**Interfaces:**
- `SamanvayClient(String baseUrl, String tokenUrl, String clientId, String clientSecret, Clock)`; methods mirror spec 4.1: `resolve(assertion)`, `startLink(citizenId, dept, returnTo)`, `completeLink(citizenId, assertion)`, `readiness(code, citizenId)`, `consentRequest(citizenId, code)`, `grantConsent(statement)`, `start(code, citizenId, form)`, `applications(citizenId)`, `application(ref)`, `steps(ref)`, `records(ref)`.
- `ConsentSigner(ECKey key)`: `String sign(ConsentWording w, UUID citizenId, String personId, String deptCode, Instant now)` produces the spec 3.4 JWS (`typ=samanvay-consent`, key in header).
- `PortalSession`: `String issue(Person p, Instant now)` / `Optional<Person> read(String cookie, Instant now)`, HMAC-SHA256, 8h TTL, tamper and expiry tests.
- `JourneyCatalog.load(InputStream)` from `journeys.json`; `List<Map<String,Object>> manifestJourneys(String baseUrl)`.
- `PortalController`: routes of spec 3.3; session via cookie `samanvay_dept_session`.

- [ ] **Step 1: Failing tests:** client caches the token until near expiry and never sends a user-controlled department; signer output verifies with the public key and has the required claims and `exp <= iat + 5min`; session rejects tampered/expired cookie; BFF routes require a session (401), `/portal-api/journeys/{code}` is served for every journey in the catalog.
- [ ] **Step 2-4:** Fail, implement, run `./mvnw -q -f departments/pom.xml -pl kit test`, then `./mvnw -q -f departments/pom.xml test` to confirm the four departments still build and pass.
- [ ] **Step 5:** Commit `feat(departments): kit module with Samanvay client, consent signer, portal session and BFF`.

### Task 8: Department changes, four times (one commit per department)

For each of Education, Agriculture, Revenue, DBT (order: Education, Agriculture, Revenue, DBT):

**Files:**
- Create: `departments/<d>/src/main/resources/journeys.json`; `<D>PortalConfig.java` wiring `PortalController` beans (key, client config, `Brand`)
- Modify: `LoginController` (direct `/login` = portal sign-in; `return_to` allow-list now includes every department's `/portal/callback` and Samanvay is no longer needed), `AssertionSigner.sign(personId, state, nonce, name, dob)`, `<D>ManifestController` (journeys generated from `JourneyCatalog`, plus `portalUrl`), `application.yml` (`samanvay.base-url`, `samanvay.token-url`, `samanvay.client-id`, `samanvay.client-secret`, `portal.session-secret`), `CitizenStore` + `Jdbc*`/`Configured*` (return display name and DOB; Agriculture: add `date_of_birth` to schema and seed), `index.html` -> redirect to `/portal/`
- Test: `<D>PortalTest` (BFF with `FakeSamanvay`), `<D>ManifestJourneysTest` (every manifest journey is served by `/portal-api/journeys/{code}`), `<D>LoginDirectTest` (`GET /login` without params returns the sign-in page, not the invalid-link page; `POST` signs in and sets the portal cookie and redirects to `/portal/`; foreign `return_to` still refused), update `LandingPageTest` (home now redirects to `/portal/`; error pages unchanged)

Journey definitions (codes must match the existing registry journeys in `src/main/resources/db/migration/V62...`; read it and `scripts/gen-demo-data.py` for the real codes):
- Education: scholarship, needs Revenue (income) and DBT (bank account).
- Agriculture: farmer subsidy, needs Revenue (land) and DBT (bank account).
- Revenue: income certificate renewal, needs its own prior income record and DBT-less (no external category); the journey still goes through Samanvay for the audit trail.
- DBT: bank-account seeding, needs Revenue (identity/income proof) if the registry has such a category; otherwise a single-source journey.

- [ ] **Step 1:** Write the three tests for the department (fail: missing classes/behaviour).
- [ ] **Step 2:** Run `./mvnw -q -f departments/<d>/pom.xml test` and confirm they fail for the expected reason.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the department's whole test suite; green.
- [ ] **Step 5:** Commit `feat(<d>): own portal backend, journeys.json, manifest from journeys, direct sign-in`.

### Task 9: Portal front end

**Files:**
- Create: `frontend/portal.html`, `frontend/src/portal/{main.tsx,App.tsx,api.ts,theme.css,Services.tsx,Journey.tsx,ConsentStep.tsx,Track.tsx,Callback.tsx}` + tests `*.test.tsx`
- Modify: `frontend/vite.config.ts` (second entry; separate `dist-portal` build via `--mode portal` and `base: '/portal/'`), `frontend/package.json` (`build:portal`), i18n resource files
- Create: `scripts/build-portal.sh` (build, copy `dist-portal` to each `departments/<d>/src/main/resources/static/portal/`), `.gitignore` entry for those copies

**Interfaces:** consumes the `/portal-api` routes of Task 7 exactly; portal config (name, accent) from `GET /portal-api/me` or `GET /portal-api/config`.

Screens: Services list; Journey page with steps Apply form (from definition), Connect departments (each: Connected, or "Log in at <Department>" which POSTs `links/{dept}` and navigates to the returned URL), Consent (exact wording, one-time code input, confirm), Submit; Track list and detail (steps, records); Callback screen (calls `/portal/callback` handling, then routes back). Loading skeletons, empty and error states, 44px touch targets, no dashes/emojis.

- [ ] **Step 1: Failing tests:** Services renders journey names from the API; Journey disables Submit until all connections and consent are done; clicking "Log in at Revenue" posts and navigates to the returned URL; Consent shows wording verbatim and posts the code; Track shows steps in order; every screen has a labelled control and an error state (mock failing fetch).
- [ ] **Step 2:** `cd frontend && npm test -- --run src/portal` — fail.
- [ ] **Step 3:** Implement screens and styling (per department accent, one accent only).
- [ ] **Step 4:** `npm test -- --run && npm run lint && npm run typecheck && npm run build:portal` all pass.
- [ ] **Step 5:** Commit `feat(frontend): department portal`.

### Task 10: Staff per-journey page

**Files:**
- Create: `ops/internal/web/JourneyStatusController.java`, `ops/internal/service/JourneyStatusService.java`, `frontend/src/pages/JourneyStatusPage.tsx` (+test)
- Modify: router and Catalog page links, i18n
- Test: `JourneyStatusControllerTest`, `JourneyStatusPage.test.tsx`

**Interfaces:** `GET /api/ops/journeys/{code}` (officer, admin) -> `{code, name, requester, portalUrl, categories:[{category, connectorRef, version, status, sourceHealth, lastTrial}], counts:{running, completed, failed}, recent:[{id, ref, state, startedAt}], log:[{at, instanceRef, category, connector, outcome, latencyMs, error}]}`.

- [ ] **Step 1: Failing tests:** a journey with an active connector and a failed instance shows both in counts and the failed step in the log with its error; unknown code 404; citizen/department roles 403; page shows a "Not connected" row when a category has no active connector.
- [ ] **Step 2-4:** Fail, implement, run `./mvnw -q test -Dtest='Journey*,Ops*'` and `npm test -- --run`.
- [ ] **Step 5:** Commit `feat(ops): per-journey status page with connector health and log`.

### Task 11: End-to-end test through the department services

**Files:**
- Modify: `src/test/java/.../DepartmentsEndToEndIT.java` (find with `grep -rl DepartmentsEndToEnd src/test`)

- [ ] **Step 1:** Extend the IT, test first: for each of the four journeys: sign in at the home department (over HTTP, password + 123456), portal `me`, readiness shows missing links, link each required department (login at that department, callback), consent via the BFF (wrong code refused, right code accepted), submit, track to a terminal state, records visible. Plus Education-then-Agriculture merge scenario. Expect failure first where wiring is missing.
- [ ] **Step 2-4:** Run `./mvnw -q verify -Dit.test=DepartmentsEndToEndIT`; fix wiring until green; run the full Samanvay suite `./mvnw -q verify` and all department suites.
- [ ] **Step 5:** Commit `test: end-to-end journeys on the four department portals`.

### Task 12: Deployment, docs, redeploy and browser check

**Files:**
- Create: `scripts/provision-department-clients.py` (Keycloak admin API: set secrets of `dept-<code>` clients from the generator state; idempotent), a test for the generator additions (`scripts/test_gen_demo_data.py`)
- Modify: `scripts/gen-demo-data.py` (per-department env: Samanvay URL, token URL, client id/secret, portal session secret, allowed return URIs of all departments), `deploy/**` compose and README (portal assets, unset citizen issuer, no citizen realm import), `keycloak/gen_realms.py` if needed, `docs/FINAL-CHANGES.md` phase 8, `docs/contracts/login-assertion.md` (name, dob claims), a new `docs/contracts/department-api.md` (spec 4.1) and consent statement contract
- Memory: update `department-services-redesign.md`

- [ ] **Step 1:** Extend `test_gen_demo_data.py` for the new env keys (fail), implement generator changes, pass (`python scripts/test_gen_demo_data.py`).
- [ ] **Step 2:** Run `scripts/build-portal.sh`, rebuild the four department images and the Samanvay image; confirm `./mvnw -q verify` green.
- [ ] **Step 3:** Redeploy the five servers (same method as the earlier deployment in `memory/aws-demo-deployment.md`): copy bundles, run provisioning, restart services; ask before any destructive action.
- [ ] **Step 4:** In the browser pane, per department: open the site, sign in with a generated citizen, run the journey (cross-department link included), and check the staff journey page on the middle layer. Record results.
- [ ] **Step 5:** Commit docs and scripts: `docs+deploy: department journeys deployment and contracts`. Open a PR when the user asks.

---

## Self-review

- **Spec coverage:** 1 (intent) -> Tasks 8, 9, 11; 3.1-3.3 -> Task 7, 8; 3.4 + 4.2 -> Task 3; 4.1, 4.3 -> Tasks 1, 2, 4; 4.4 -> Task 4; 4.5 -> Task 5; 4.6 -> Task 5; 4.7 -> Task 6; 4.8 -> Task 10; 5 -> Task 8; 6 -> Task 9; 7 -> Review Focus, Tasks 1-4; 9 -> every task; 10 -> Task 12.
- **Known soft spots, resolved while implementing (each starts by reading the named files and records its decision in the commit message):** exact registry journey codes and category names (Task 8 reads `V62` and the generator); whether `identity_profile` needs an "unverified DOB" marker (Task 2); nonce storage location (Task 3).
- **Type consistency:** `VerifiedAssertion` (Task 1) feeds `CitizenResolver.resolve` (Task 2); `CitizenResolution` and `{citizenId}` bodies match the kit client methods (Task 7); consent request/statement fields match between Tasks 3 and 7.
