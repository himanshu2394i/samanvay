# Samanvay — session handoff: real onboarding, the "is it real?" question, and the TODO backlog

**Date:** 2026-09-30. **Audience:** the next agent (or Himanshu) continuing this work. Self-contained — do not assume access to the prior chat.

---

## 0. The user's core vision (read this first)

> "I want: I onboard a **real service** (even with fake data and documents), and when I plug in / onboard that department, **the layer actually works** — not that we just stub our data into it. I made those 4 real department services for this so they run independently like real departments and our layer can work for real."

So the north star is: **onboarding a department + connector should produce a genuinely working, live data path** (real protocol, real network, real mapping, consent-gated), demonstrably — not a mock. The 4 department simulators (`simulators/`, running as docker containers: `department-service` :8090 REST/SOAP, `department-db` JDBC, `department-sftp` SFTP) exist to be those "real departments."

The user is (rightly) skeptical after seeing a **fake department "Testing001" register as ACTIVE with no checks**. Section 2 explains what is real vs. not, and Section 4.3 is the fix that makes onboarding *demonstrably* real.

---

## 1. Current deployment state (live now)

- **Repo:** `main` @ latest (PRs #64,#66,#68,#69,#70 merged earlier; #71 = SPA-in-jar + design; #72 = Keycloak HTTPS origins). Local `main` == origin == AWS.
- **AWS:** single EC2 `i-04afcaf2f340dfdfe`, ap-south-1, public IP **3.109.201.126** (NOT elastic — a stop/start changes it and breaks the nip.io names + certs; allocate an Elastic IP for stability).
- **App** runs as systemd service `samanvay` (reboot-safe). Redeploy: `ssh … ; cd ~/samanvay ; git pull ; ./mvnw -Pfrontend -DskipTests package ; sudo systemctl restart samanvay`.
  - The `frontend` Maven profile builds the React SPA and bundles it into the jar at `static/app` (served at `/app/`). CI/normal `mvn verify` is unaffected (profile is opt-in).
- **HTTPS via Caddy + nip.io** (real Let's Encrypt certs on a bare IP):
  - **SPA:** https://app.3.109.201.126.nip.io/app/
  - **Portals (interop story):** https://app.3.109.201.126.nip.io/
  - **Keycloak:** https://auth.3.109.201.126.nip.io/  (admin console `/admin/`)
  - **Citizen email inbox (Mailpit):** https://mail.3.109.201.126.nip.io/  ← read sign-in codes here (exposes ALL codes — demo only)
- **Reboot-durable:** Caddy + app systemd-enabled; containers `restart: unless-stopped`; Keycloak public hostname + admin password in `~/samanvay/docker-compose.override.yml`; HTTPS origins baked into the realm exports (PR #72). Ephemeral H2 Keycloak → a container *recreate* re-imports realms (origins survive); TOTP enrolments do NOT survive a recreate.
- **Credentials** (dev realm):
  - Staff (Keycloak OIDC, first login forces password change + TOTP enrol): `dev-officer`/`dev-officer-change-me` (OFFICER, has the Citizen-360 view), `dev-admin`/`dev-admin-change-me` (ADMIN — onboarding/catalog/metrics/audit), `dev-reviewer`/`dev-reviewer-change-me`.
  - Citizen: username `dev-citizen`, then the email code from Mailpit.
  - Static portals dev shortcut: `officer`/`demo-2026`.
- **Harness note:** the assistant is blocked by guards from: admin-merging PRs, opening SG ports beyond 80/443, exposing services (Caddy setup), and reading Mailpit sign-in codes. **The user runs those steps.** Scripts for them live in the session scratchpad: `enable-https-keycloak.sh`, `finish-https-keycloak.sh`, `durable-and-citizen.sh`.
- Full deploy detail is in the memory `aws-demo-deployment` and `spa-vs-static-frontend`.

---

## 2. Is the system real, or stubbed? (grounded answer)

**The runtime data path is REAL.** When a citizen applies, orchestration calls the connector runtime, which makes a **real network call** to the department source via real protocol adapters (`RestAdapter`, `SoapAdapter`, `JdbcAdapter`, `SftpAdapter`) and maps the response. Proven by real transport integration tests: `JdbcRealTransportTest`, `SftpCsvRealTransportTest`, `SoapAdapterRealTransportTest`, `DeadlineHttpTest`, `RealSourcesOnboardingTest`, `JourneysRealResolutionTest`. In the demo/dev profile these sources point at the 4 simulator services (real network, real protocols, fake data). **This is genuinely working.**

**Why "Testing001" registered as ACTIVE with no check** — this is expected, not a bug, but it *looks* fake:
- `CatalogServices.register(...)` (`src/main/java/com/samanvay/catalog/internal/service/CatalogServices.java:143`) sets `status = "ACTIVE"` unconditionally. **Registering a department is just a catalog metadata entry.** A department is an organisational entity; whether anything is *reachable* is a property of its data source + connector, not the department name. So any name you type is "active" (= registered).

**The three onboarding checkpoints today:**
1. **Data source** (`CatalogServices.java:153` `hosts.assertAllowed(draft.baseHost())`, `DataSourceHostPolicy.java`): the base host is validated against a security policy (SSRF guard — rejects blank/private/loopback/localhost hosts). This is a **real check**. `healthStatus` is set to `"UNKNOWN"` (not probed).
2. **Connector "Test" step** (`CatalogServices.java:120-131`): validates the connector *config* — capabilities present + the mapping ref resolves. It **does NOT make a live call to the source.** "Test passed" currently means "config is coherent," not "we reached the department." Publish requires `testReport.passed()` (`:227-228`).
3. **Runtime** (the real one): the live fetch on an actual application. If the department is down/unreachable, it fails here → `PENDING_SOURCE`/`FAILED` → exception queue.

**The gap (and the user's real ask):** onboarding never does a **live connectivity probe**. The "Test" step *sounds* like it should hit the source but only checks config. To make onboarding *demonstrably* real (and to answer "is it real?" convincingly on camera), the Test step should do a **real trial fetch** against the source and populate `healthStatus`. See TODO 4.3.

---

## 3. How onboarding works (and answers to the specific questions asked)

**Onboarding wizard** (`frontend/src/surfaces/staff/pages/OnboardingPage.tsx`, ADMIN): Department → Data source → Connector (draft) → Field matches (OpenAPI import) → Test → Publish. Endpoints in `CatalogController.java` (`/api/catalog/*`).

- **"Identity provider realm" field (Department step):** the Keycloak realm that brokers *that department's* citizen sign-in (used by the "Department sign-in" link option, `DEPT_IDP`). For this demo write **`samanvay-department`** (the mock department realm). For a real department it'd be their actual IdP realm/alias.

- **"How does the connector discover the document type?"** — it does **not**. The admin **picks** the document type (category) from a dropdown in the Connector step (`CATEGORIES` in `frontend/src/surfaces/staff/lib/onboarding.ts`). A connector serves exactly **one** category, declared by the admin. What the **OpenAPI import (Field matches step) discovers is FIELD matches** — lexical guesses mapping source fields → the target schema's fields (propose-only; the admin ticks the ones to keep). It never discovers the *type*; it suggests field mappings *within* the chosen type.

- **"How do we onboard the JOURNEYS in a department — can we aid that too?"** — **Journeys are currently NOT self-service.** The catalog API is **read-only for journeys** (`CatalogController.java`: only `GET /journeys`, `GET /journeys/{code}` — there is no journey write endpoint, no `JourneyDraft`). A new journey (e.g. the farmer subsidy) is added as **seed/config by an engineer** (Flyway migration / catalog config) — "no new Java module" (that's the achievement: journeys are data, not code), but **not** via the UI. So the department/source/connector/mapping are UI-onboardable; the *journey* (which categories a service needs, SLA hours, purpose code, category→source mapping) is not. **Making journeys self-service is a real feature to build** — see TODO 4.4.

---

## 4. TODO backlog (prioritised, with detail)

### 4.1 — Fix the 5-minute logout  (SMALL, urgent, do first)
**Cause:** both realms set `accessTokenLifespan: 300` (5 min), and the SPA has `automaticSilentRenew: false` (`frontend/src/auth/oidc.ts:28`) — so the access token expires after 5 min, the next API call 401s, and the app drops the session. The comment there ("no refresh token is requested") is now the thing to change.
**Fix:** set `automaticSilentRenew: true` in `createUserManager` (`oidc.ts`). Keycloak issues a refresh token on the code flow by default; oidc-client-ts v3 will renew silently via the refresh_token grant (CORS is allowed — the app origin is in the realm `webOrigins`). Update the doc comment. Run the auth tests (`AuthProvider.test.tsx`, `AuthProvider.staff.test.tsx`, `config.test.ts`) + `npm run build`.
**Instant workaround for the user (no redeploy):** Keycloak admin → https://auth.3.109.201.126.nip.io/admin/ → realm dropdown → `samanvay-staff` (and `samanvay-citizen`) → Realm settings → Tokens → **Access Token Lifespan → 30 minutes → Save**. (Resets on a Keycloak recreate; for durability also bump `accessTokenLifespan` in the two realm export JSONs.)

### 4.2 — Connect-flow UX (the "cheap wins" the user asked for) — scope into a PR
Rework `ConnectStep` / `DepartmentCard` in `frontend/src/surfaces/citizen/pages/ApplyPage.tsx`.
- **Win A (cheap, safe): hide/lock the "ID type".** Today `DepartmentCard` renders an editable `Field label={t('dept.idType')}`. The type is config (`need.localIdType ?? need.departmentCode`) — it should not be a citizen-facing editable field. Remove the input; keep sending `localIdType` from config (so the existing test assertion `localIdType:'REVENUE'` still holds). Existing tests don't interact with that field, so Win A is safe.
- **Win B (bigger — NOT actually "cheap"): one shared provider + a single "Connect all" action** instead of a per-department provider dropdown + a Connect button per department (the "why 3 times" complaint). Design: hoist the provider choice to one shared `<select>` (default DigiLocker); list departments compactly (each shows what it provides + one id input, + OTP only when provider is `LOCAL_ID_OTP`); one "Connect accounts" button that loops `assertLink` over unlinked departments. **Important correctness note:** the mock DigiLocker (`DigiLockerLinkProofProvider.verify` → `sandboxBind()`) just **echoes back the `localId` you pass**, and the subsequent record *fetch* needs each department's real local id — so "one DigiLocker login, zero typing" would silently break fetching. Keep collecting each id. True zero-typing needs the mock DigiLocker to *return* per-department identifiers (a backend change).
  - This rewrites the connect tests in `frontend/src/surfaces/citizen/citizen.flow.test.tsx` (helper `connectRevenue`, the per-card select/button assertions, the DEPT_IDP-not-offered check → now on the shared select, the "proof refused" test). Preserve: link body per department, "Connected N of M", continue gating, error handling.
  - i18n: add `connect.howProve`, `connect.oneProvider`, `connect.connectAll`, `connect.connecting` to `en.ts` + `mr.ts` (existing keys at en.ts lines ~83-105; mr.ts ~84-106). Unused `dept.idType`/`dept.howProve`/`dept.connect` can be left (harmless).
- **Verify:** `npm test` + `npm run build` + walk the citizen connect step live (log in as `dev-citizen`, code from Mailpit).

### 4.3 — Make onboarding demonstrably REAL: live probe at the Test step  (HIGH VALUE — directly answers "is it real?")
Change the connector **Test** (`CatalogServices.test(...)`, `:120`) from a config-only dry check into a **real trial fetch** against the data source: use the same connector runtime/adapter the live path uses to make one sample call, and set the data source `healthStatus` (currently always `"UNKNOWN"`) to `HEALTHY`/`UNREACHABLE` based on the result. Then a broken/unreachable source **fails at Test time**, not silently at runtime, and the wizard proves the department is actually wired. Surface `healthStatus` in the catalog UI. This is the single most convincing "it's real" improvement for the demo. (Backend + a bit of frontend; add an IT that points at a simulator and asserts a real probe passes, and a down host fails.)

### 4.4 — Self-service journeys ("aid the journey onboarding")  (LARGER feature)
Today journeys are read-only config (Section 3). To make adding a service as easy as onboarding a department: add a **journey write path** — `POST /api/catalog/journeys` (a `JourneyDraft`: code, name, required categories, per-category source mapping, SLA hours, purpose code, reference prefix) + validation (categories must have published connectors; sources must resolve) + a **"Create a service" wizard** in the staff UI (ADMIN). The orchestration already resolves journeys generically (`JourneysRealResolutionTest`), so much of the runtime is journey-agnostic — the main new work is the write/validate path + UI. Confirm what per-journey wiring (if any) still needs code vs. becomes pure config; aim to shrink the code side to near-zero so a new service is fully self-service.

### 4.5 — Fallback when a department can't provide a record  (architecture, discussed)
Real department systems fail; there must be a degraded path — **without** storing citizen data centrally (the user correctly rejected "store uploads in the middle layer" — it recreates the honeypot and breaks the control-plane/data-plane split). Design in tiers:
1. **Retry** (transient) — orchestration; already exists (exception queue + officer Retry).
2. **Officer manual verification** — officer attests an out-of-band check; only the *decision* + audit entry are stored, no document.
3. **Citizen supplies proof** (last resort) — upload **scoped to that one application**, held transiently and **purged after the decision** (or a short retention window), ideally pushed to the *owning department's* system; **never** indexed as reusable citizen data.
- **Where it lives:** the *policy* (is a fallback allowed, for which categories, after how many retries) = **journey/catalog config**; the *mechanism* (queue, manual-verify, scoped upload) = **middle-layer orchestration**; the *data* = never Samanvay's durable core.
- **Precedent already in the code:** the **bank-account review** flow already does tier 3 — when the automated check can't verify, officer *requests a document* → citizen *uploads a passbook* → officer approves/rejects (`staffApi.uploadPassbook`/`requestDocument`; `BankReview`). **ACTION: trace how that passbook is stored** — confirm it's ephemeral/scoped (honours the principle) or add a retention/purge fix — then generalise that pattern to other categories via journey config.
- Judge-ready one-liner: "If a department is offline: retry → an officer verifies manually → as a last resort the citizen attaches proof for that one application, consent-logged, never stored centrally."

### 4.6 — Infra polish
- **Elastic IP** so the demo survives a stop/start (otherwise the IP changes and nip.io names + certs die). Then bake the stable name into the realm origins instead of the current IP.
- Change the public Keycloak **admin password** if not already done: `docker exec samanvay-keycloak-1 /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8180 --realm master --user admin --password admin && docker exec … set-password -r master --username admin --new-password <STRONG>` (and set `KC_BOOTSTRAP_ADMIN_PASSWORD` in the override for durability).
- Tear down when done (costs): see the `aws-demo-deployment` memory (terminate instance, remove SG rules, disable caddy/samanvay units).

---

## 5. Key files (map for the next agent)
- Onboarding UI: `frontend/src/surfaces/staff/pages/OnboardingPage.tsx`; helpers `frontend/src/surfaces/staff/lib/onboarding.ts`.
- Catalog backend: `src/main/java/com/samanvay/catalog/internal/service/CatalogServices.java` (register `:135`, data-source host check `:153`, connector `test()` `:120`, `publish()` `:227`), `…/web/CatalogController.java`, `…/service/DataSourceHostPolicy.java`.
- Connect flow (citizen): `frontend/src/surfaces/citizen/pages/ApplyPage.tsx`, `…/lib/applyFlow.ts`; tests `…/citizen.flow.test.tsx`.
- Identity link proofs: `src/main/java/com/samanvay/identity/internal/proof/*` (DigiLocker mock echoes context in `sandboxBind()`).
- Auth/session: `frontend/src/auth/oidc.ts` (`automaticSilentRenew`), realm token lifespans in `keycloak/realms/samanvay-*-realm.json` (`accessTokenLifespan: 300`).
- Officer Citizen-360 (built this session): `frontend/src/surfaces/staff/pages/CitizenViewPage.tsx` (route `/staff/officer/citizens/:citizenId`).
- Real protocol adapters + tests: `src/main/java/com/samanvay/connector/internal/protocol/*` and the `*RealTransportTest` files.
- Demo narrative: `docs/demo/JUDGE_SCRIPT.md`, `docs/demo/ONE_PAGER.md`.

## 6. What was completed this session (context)
Merged 5 queued PRs; built the officer **Citizen-360** view; a calm design pass (rejected-status stepper reads red; landing privacy-assurance panel, bilingual); wired the **SPA into the jar** (served at `/app`); **redeployed AWS** and moved it to **systemd**; added **HTTPS + public Keycloak** (Caddy + nip.io + LE), made it **reboot-durable**, and turned on **public citizen login** (Mailpit exposed). Frontend suite green (205 + 3 new for Citizen-360). Produced an SIH **demo video script** (in the chat transcript — re-create from `JUDGE_SCRIPT.md` + the SPA flow if needed).

## 7. Immediate next actions (suggested order)
1. TODO 4.1 (logout fix) — small, unblocks testing.
2. TODO 4.2 Win A (hide ID type) — cheap, safe; then Win B if wanted.
3. TODO 4.3 (live probe at Test) — makes onboarding demonstrably real; best answer to the user's core concern.
4. TODO 4.4 (self-service journeys) — the bigger feature to "aid journey onboarding".
5. TODO 4.5 (fallback) — start by tracing the passbook storage.

---

## 8. Expanded onboarding vision (2026-09-30, added at the user's request)

The user wants onboarding to be a **live, self-proving loop**: remove one of the real departments, onboard it fresh through the UI, and have the layer actually **connect to it, discover what documents it exposes and what journeys need them**, then manage those journeys — not stub anything.

### 8.0 Simulator facts (the "real departments")
`SandboxDepartmentController` (`simulators/…/department/SandboxDepartmentController.java`) on **:8090** (container `samanvay-department-service`):
- `GET /v1/income?rationCard=...` → JSON `{annualIncome, holderName, district, issuerOffice}` (REST) — REVENUE income.
- `GET /bank?dbtId=...` → JSON `{accountRef, ifscMasked, holderName}` (REST) — DBT bank.
- `POST /marks/service` (SOAP 1.1, text/xml, `<studentId>`) → `{percentage, board, exam}` — EDUCATION marks.
- SFTP property (container `samanvay-department-sftp`, creds `fixtureuser:fixturepass`) and JDBC pollution (`samanvay-department-db` :5433, creds `pcb_ro:pcb_ro_demo`).
No auth on the REST endpoints (open GET). Departments/sources/connectors/journeys are seeded by Flyway V21/V22/V23/V193/V198/V199 (`src/main/resources/db/migration/`).

### 8.1 Live-onboard demo (Level A works TODAY; Level B is the build)
- **Level A (real runtime, already works):** if the user registers a department → data source (host allow-listed) → connector → mapping pointing at a simulator endpoint, then a real citizen application **really fetches** from it (real adapter over the network). So "onboard a department and it connects" is achievable now — the missing piece is only *discovery* and *self-service journeys*.
- **Recommended department to remove & re-onboard first:** **DBT** (bank, REST `GET /bank?dbtId=`) — simplest (REST + OpenAPI import fits), one document (BANK_ACCOUNT). Blast radius: scholarship + farmer bank step (both recover once re-onboarded). REVENUE (income **and** caste) is the ideal example for §8.3 (multiple documents, one department).
- **Onboarding parameters to hand the user for DBT bank:** Department code `DBT`, name "Direct Benefit Transfer", idp realm `samanvay-department`; Data source protocol `REST`, base host = the department-service host (the SG/allow-list must permit it — in the deployed compose it's the container hostname), auth `NONE` / `secret:none`; Connector category `BANK_ACCOUNT`; mapping target `Credential/BankAccount@1` (verify the exact schema ref via the wizard's schema list); OpenAPI operation returning `{accountRef, ifscMasked, holderName}` mapped onto that schema.
- **Removal:** delete the DBT department + its data source + connector + mapping + journey-source wiring from the running catalog (Postgres) OR add a Flyway migration that removes them (preferred for local==deployed). **TODO: generate the exact DELETE/migration after reading the catalog schema** (tables: catalog department, data_source, connector, mapping, journey source-binding — names to confirm). Note the affected journeys will show `PENDING_SOURCE` until re-onboarded (which is the point of the demo, and ties to §4.5 fallback).

### 8.2 Discovery endpoints on the department service (Level B — build)
Add a **discovery/manifest endpoint** to the simulator (and define it as the contract a real department would expose), e.g. `GET /.well-known/samanvay-manifest` returning: the documents this department issues (category, input params, output fields, protocol/endpoint) and optionally the journeys it participates in. Then the **onboarding wizard consumes it**: point onboarding at the department host → it fetches the manifest → auto-lists the documents (instead of the admin hand-typing category + OpenAPI). This is what makes onboarding feel like "plug in the department and it discovers everything." (Could reuse OpenAPI: the department serves an OpenAPI doc at a known path and the wizard imports it automatically rather than pasting JSON.)

### 8.3 Multiple documents from one department — do NOT re-onboard the department
Answer to the user's question: **no.** The model is **department + data source registered ONCE**, then **one connector per document/category** (a connector serves exactly one category — see §3). So 4 documents from one department = 1 department + 1 (or few) data source(s) + **4 connectors**. **UX gap to fix:** the current wizard runs department→source→connector→mapping→test→publish as a single linear pass, which *implies* re-onboarding. Add an **"add another document to this department"** entry point (reuse an existing department + data source; jump straight to a new connector+mapping+test+publish). This directly answers the user and removes the confusion.

### 8.4 After mapping: journey view + per-journey controls (Level B — build)
After onboarding, show a **department detail page**: the department's documents (connectors, health), and the **journeys** that use them — each journey with what documents it requires, whether the middle layer is **active** for it (all required connectors published + reachable), and **per-journey controls/settings**: enable/disable, SLA hours, consent purpose, retry/fallback policy (ties to §4.5), acceptable staleness. This needs the **self-service journey write path** (§4.4: `POST /journeys` + validation) plus a read model that joins journey → required categories → connector health. This is the "middle layer is active in it with more controls" the user described.

### 8.5 Build order for this vision (suggested)
1. §4.1 logout fix (done — needs PR/redeploy) and §4.3 live probe (proves connectivity) — foundation for "it really connects."
2. §8.2 discovery manifest on the simulator + wizard auto-import — the headline "plug in and discover."
3. §8.3 "add another document" flow — removes the re-onboard confusion.
4. §4.4 + §8.4 self-service journeys + per-journey controls — the management layer.
5. §8.1 remove-a-department migration + hand the user creds to re-onboard live — the end-to-end demo.
Each is a real chunk; expect multiple PRs. None require storing citizen data (keep the control-plane/data-plane split from §4.5).

### 8.6 Un-onboard a department (keep the simulator running) + the reachability gap
"Remove" = un-onboard (delete the catalog rows), **not** delete the simulator. Catalog schema + FKs (`V20__catalog_init.sql`): `catalog_data_source.department_code`→`catalog_department`, `catalog_connector.data_source_code`→`catalog_data_source`, `catalog_mapping.connector_ref`→`catalog_connector`; `catalog_journey` has **no** FK to department (categories are a `TEXT[]`, sources live in `policy` JSON). So un-onboarding **DBT** is a clean child→parent delete (run as the migrate superuser inside the container):
```sql
BEGIN;
DELETE FROM catalog_mapping     WHERE connector_ref = 'dbt-bank@1';
DELETE FROM catalog_connector   WHERE ref = 'dbt-bank@1';
DELETE FROM catalog_data_source WHERE code IN ('dbt-rest-mock','dept-bank-rest');
DELETE FROM catalog_department  WHERE code = 'DBT';
COMMIT;
```
`ssh … 'docker exec samanvay-postgres-1 psql -U samanvay_migrate -d samanvay -v ON_ERROR_STOP=1 -c "<the SQL on one line>"'`. Effect: scholarship + farmer **bank** step goes `PENDING_SOURCE` until DBT is re-onboarded (that's the demo; ties to §4.5).

**CRITICAL reachability gap (why a naive re-onboard won't connect):** the seeded sources reach the simulator only because (a) they were **inserted via SQL, bypassing `DataSourceHostPolicy.assertAllowed`** (the SSRF guard that rejects blank/loopback/private/unresolvable hosts — `CatalogServices.register` calls it, the seed does not), **and** (b) the demo profile remaps them to the real service **by source CODE** (`application-demo.yml` → `samanvay.sources.department-service.urls: { dept-bank-rest: http://localhost:8090, … }`), not by host. So a source freshly created through the **wizard** must: pass `assertAllowed` (its `base_host` must be public-looking **and resolvable**), and then actually be reachable — but the runtime will try the literal `base_host`, which won't be the simulator unless it's remapped. **To make self-service onboarding genuinely connect, do ONE of:**
1. Give the simulator a real, allow-listed, resolvable hostname the app can reach (e.g. expose `department-service` at a nip.io host + an `/etc/hosts`/DNS entry), and onboard against that host (no code-remap needed). **← cleanest; unblocks the whole §8 vision.**
2. Or extend the demo URL-remap to be keyed by host (or add the new source code to the remap) — demo-only hack.
3. Or relax `DataSourceHostPolicy` for an explicit demo allow-list of simulator hosts.
Until one of these is done, "onboard DBT and watch it fetch live" will fail at the fetch even though registration succeeds. This is the concrete blocker to close first for the live-onboard demo (fold into §4.3/§8.2).

### 8.7 SOLVED (2026-09-30): distributed departments via real hostnames — NO middle-layer code change
Verified facts that make the live-onboard work end-to-end:
- **Allow-list** (`DataSourceHostPolicy.assertAllowed`) rejects only private/loopback/link-local prefixes + resolved private addresses; a **public hostname passes** (and an unresolvable host also passes, since `resolveHost` returns null and the address check is skipped — but it then fails at fetch). So a public nip.io host is accepted with **no code change**.
- **Adapter scheme is `https` by default** (`RestAdapter`/`SoapAdapter` construct `scheme + "://" + host` for non-remapped sources; the demo remap is keyed by source *code*). So a wizard-onboarded source is fetched at `https://<base_host><endpoint>`.
- **Hairpin works**: the instance reaches its own public IP (tested `http://3.109.201.126:8080/` → 200), so it can reach `<dept>.3.109.201.126.nip.io` (→ public IP → Caddy → simulator).
**Solution (no new servers, no SG change, no code change):** give each department its own Caddy vhost → `revenue.3.109.201.126.nip.io` / `dbt.…` / `education.…` → `reverse_proxy 127.0.0.1:8090` (all hit the one simulator; split onto separate boxes later for true physical independence). Script: `scratchpad/department-hostnames.sh` (user runs — Caddy edits are exposure-guarded for the assistant). Then **onboard DBT** in the wizard with **base host `dbt.3.109.201.126.nip.io`, protocol REST, auth NONE, category BANK_ACCOUNT, endpoint `/bank`**, mapping `accountRef→accountRef` onto `Credential/BankAccount@1`. The runtime then fetches `https://dbt.3.109.201.126.nip.io/bank?dbtId=<linked id>` live. **This is the genuine "plug in a real, independently-addressed department and the layer connects" moment.**
- **Remaining nicety (optional):** the wizard's **Test** step still only checks config (§4.3) — it won't *prove* connectivity during onboarding. Adding a real trial-fetch at Test (backend) would make onboarding show green/red live. Recommended next PR.
- **For true physical independence** (the user's "other servers"): deploy the `simulators/` app on separate hosts (another EC2, or a free PaaS like Render/Fly giving `something.onrender.com`) and onboard against those public hostnames — identical flow, no hairpin needed. The single-instance nip.io approach above is the cheapest way to demonstrate it now.

## 9. Standardized department discovery manifest (the "publish what you hold" contract)

The vision: each department publishes, at a well-known path, non-sensitive **capability metadata** — the documents it holds (structure + how to fetch) and the journeys it offers (with required document categories). Then onboarding is just "enter the base URL" → the middle layer fetches the manifest → auto-creates department + data source + connectors + suggested mappings, and can monitor/kill per department and per journey. The department's only change is publishing this manifest (minimal, aided — not avoiding the department-side change, just shrinking it).

### 9.1 DONE (department side): manifest endpoint on the simulator
`GET /.well-known/samanvay/manifest` → `simulators/…/department/SamanvayManifestController.java` (+ `SamanvayManifestTest`, green). Contract:
```json
{
  "manifestVersion": 1,
  "department": { "code": "SANDBOX", "name": "...", "description": "..." },
  "documents": [
    { "category": "BANK_ACCOUNT", "title": "Bank account", "protocol": "REST", "method": "GET",
      "path": "/bank", "inputs": [ { "name":"dbtId","in":"query","required":true,"description":"..." } ],
      "fields": [ { "name":"accountRef","type":"string","sensitive":true }, ... ] }
  ],
  "journeys": [ { "code":"...","name":"...","requiredCategories":["INCOME_CERTIFICATE","BANK_ACCOUNT"] } ]
}
```
`sensitive` flags personal-data fields; **values are never in the manifest** (capability metadata only). Live after the simulator container is rebuilt: `docker compose build department-service && docker compose up -d department-service` → then `https://dbt.3.109.201.126.nip.io/.well-known/samanvay/manifest`.

### 9.2 DONE (middle-layer side): consume the manifest during onboarding — PR #74
`POST /api/catalog/discover {baseUrl}` (ADMIN, `CatalogServices.discover` + `CatalogController`): fetches `{baseUrl}/.well-known/samanvay/manifest` (host guarded by `DataSourceHostPolicy` — public host required), returns the department + documents + journeys. Tests: `CatalogDiscoveryTest` (URL validation + SSRF guard). The onboarding page has a **"Discover a department from its URL"** panel (`OnboardingPage.tsx` `DiscoverPanel`, built in the existing gov design system per the taste skill's applicable craft — loading/empty/error states, no new stack): enter a URL → see the documents (with field structure + `personal` flags) and journeys → **"Register department and draft N connectors"** creates the department + a data source + a draft connector per document in one action (`onboardFromManifest`). The admin finishes mapping/test/publish per connector in the existing wizard. Full frontend suite 205 green; build+lint clean.
- **Known follow-up:** draft connectors are created with an empty `output_schema` (mapping is done in the wizard step). If `createDraft` ever rejects an empty schema, set a sensible default there. And connector `category` must be a known `DataCategory` — a department publishing a novel category needs that category registered first.
- **To go live:** merge PR #73 (logout) + PR #74 (discovery), then redeploy: on the instance `git pull && ./mvnw -Pfrontend -DskipTests package && sudo systemctl restart samanvay`, and rebuild the simulator `docker compose build department-service && docker compose up -d department-service` so `/.well-known/samanvay/manifest` is live. Then (admin) open onboarding, enter `https://dbt.3.109.201.126.nip.io`, Discover.

### 9.3 NEXT (monitoring/control): per-department & per-journey status
With the live probe (§4.3) + a periodic health check hitting each source (or its manifest), show each department/journey as connected/running/down, and allow enable/disable ("kill") a journey or department. `catalog_data_source.health_status` already exists (currently always UNKNOWN) — populate it. This is the "monitor if they're connected/running and kill a journey/department" layer.

### 9.4 Build order
1. Rebuild the simulator container so 9.1 is live; verify the manifest over HTTPS.
2. `POST /api/catalog/discover` + wizard "onboard from URL" (9.2) — the headline demo.
3. Journey write path (§4.4) so discovered journeys become real; per-journey controls (§8.4).
4. Live probe + health monitoring (§4.3, 9.3).
