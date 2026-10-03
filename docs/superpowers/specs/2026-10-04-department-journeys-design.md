# Department journeys: design

Status: approved in conversation on 2026-10-04 (parts 1 and 2), written spec approved by "go on and implement it end to end".
Branch: `feat/department-journeys`.

## 1. Intent

**Samanvay is for the Samanvay team and officers only.** A citizen never signs in to Samanvay and never sees a Samanvay screen.
Every citizen journey lives on a **department's own portal**, inside that department's own service, at that department's own address.
The department changes its own code so its journey uses the middle layer (this is the "department side change" of
`docs/FINAL-CHANGES.md` section 4). The department's manifest then describes journeys it really provides.

Success looks like this, on the deployed demo, with no Samanvay citizen screen anywhere:

1. A citizen opens `https://education.<ip>.nip.io`, signs in (mobile + password + one-time code), sees Education's services, starts the
   scholarship.
2. The portal shows which departments the journey needs records from. Revenue and DBT each have "Log in at ...", which takes the citizen
   to that department's own sign-in and back. The link is saved.
3. The portal shows the exact consent wording; the citizen confirms with the one-time code; **the department signs the consent**; Samanvay
   verifies and records it with the signed statement as evidence.
4. The citizen submits, then tracks the application and sees the records Samanvay fetched.
5. The same works for all four journeys: Education scholarship, Agriculture farmer subsidy, Revenue income-certificate renewal, DBT
   bank-account seeding.
6. Staff open one page per journey in the staff console and see whether it is connected and working, with that journey's middle-layer log.

### Decisions already made with the user

| Decision | Choice |
|---|---|
| Where journeys live | In each department's own service and portal |
| Citizen identity at Samanvay | None visible. Samanvay finds or creates the citizen quietly from the department's signed login |
| Linking other departments | Redirect (or box) to that department's own login, return, link saved |
| Consent | Collected by the department portal, **signed by the department** with its pinned key, stored by Samanvay as evidence |
| Which journeys | All four in the manifests. The licence portal is dropped (no department backs it) |
| Manifest | Derived from the department's own journey definitions, never the other way round |

### Not in scope

Deleting the inactive citizen API role and its tests (the citizen realm becomes optional and is switched off, see 5.8); real OIDC for
departments; per-department staff consoles; the licence journey; a pool for department database connections.

## 2. Architecture

```
 citizen browser ---- department portal (React, served by the department at /portal/)
                          |  same origin
                          v
                department service  (Spring Boot, one per department)
                  - login + signed assertion (exists)
                  - portal BFF  (/portal-api/**)  <- the kit library
                  - journey definitions  --->  manifest  and  portal screens  (one source)
                          |  department credential (Keycloak client dept-<code>), server to server
                          v
                Samanvay  /api/department/**   (new)   +  /api/journeys/{code}/start (exists)
                          |
                          v   staff console (officers, admins): catalog, onboarding, journey page, audit
```

The browser only ever talks to its department. The department's server part holds the department credential and the signing key.
The portal never receives a Samanvay token.

## 3. Components

### 3.1 `departments/kit` (new Maven module, shared by the four departments)

One purpose: everything a department needs to run a journey through Samanvay. A department adds a dependency and a config block.

- `SamanvayClient`: gets a token with the department's client credentials (Keycloak staff realm token endpoint), caches it until it
  nearly expires, and calls `/api/department/**`. Never logs tokens.
- `ConsentSigner`: builds and signs the consent statement (3.4) with the department's manifest signing key.
- `PortalSession`: a signed, HttpOnly, SameSite=Lax cookie holding person ID, name, DOB, time (stateless HMAC, 8 hours), like `LoginTicket`.
- `JourneyCatalog`: loads the department's journey definitions (3.2) and renders them as portal config and as the manifest's `journeys`.
- `PortalController`: the BFF, `/portal-api/**` (3.3).

Tested against a fake Samanvay (JDK `HttpServer`) so the kit has no dependency on the middle layer's code (ArchUnit boundary stays).

### 3.2 Journey definition (the single source)

`src/main/resources/journeys.json` in each department: code, name, description, `referencePrefix`, `slaHours`, `consentPurpose`,
`requiredCategories [{category, department}]`, and the form for the "Apply" step (fields with label, type, required, options).
The manifest's `journeys` array is **generated from it** (plus `portalUrl`), and the portal screens are driven by it.
Test: every journey the manifest lists is served by `GET /portal-api/journeys/{code}`.

### 3.3 Portal BFF (`/portal-api`, session required except where noted)

| Route | Does |
|---|---|
| `GET /portal-api/me` | the signed-in citizen, or 401 |
| `GET /portal-api/journeys`, `/{code}` | definitions (from 3.2) |
| `GET /portal-api/journeys/{code}/readiness` | per needed department: connected or not, plus whether consent is active (Samanvay) |
| `POST /portal-api/journeys/{code}/links/{dept}` | asks Samanvay to start the login at `dept`; returns that department's login URL |
| `GET /portal/callback` | return from the other department: completes the link with Samanvay, then redirects into the portal |
| `GET/POST /portal-api/journeys/{code}/consent` | preview the wording; confirm with the one-time code, sign, submit |
| `POST /portal-api/journeys/{code}/submit` | starts the journey in Samanvay |
| `GET /portal-api/applications`, `/{ref}` | status, steps, received records (own journeys only) |

Sign-in: `GET /login` **without** `return_to` is the portal's own sign-in (mobile, password, code), after which the portal session is set and
the citizen lands on `/portal/`. With `return_to`, state and nonce it is the Samanvay link flow, exactly as today. `/` redirects to `/portal/`.
So opening `/login` directly never shows "This sign in link is not valid".

### 3.4 The consent statement

A compact JWS, ES256, header `typ: samanvay-consent` and the department's public key (`jwk`), payload:

`{jti, iss: "dept:<CODE>", dept_code, citizen_id, person_id, purpose, request_id, nonce, categories[], valid_until, confirmed_at, method: "dept-otp", iat, exp (<= 5 min)}`

It is built by the kit **only after** the citizen has re-entered the one-time code, and only from the wording Samanvay returned for that request.

## 4. Samanvay changes

### 4.1 Department endpoints (`/api/department/**`, role DEPARTMENT, token must carry a `department` claim)

| Endpoint | Behaviour |
|---|---|
| `POST /api/department/citizens/resolve` `{assertion}` | verifies the home department's login assertion; finds the citizen linked to (department, person ID) or creates one (profile from the assertion's name and DOB) with that link; returns `{citizenId, created}` |
| `POST /api/department/links/start` `{citizenId, departmentCode, returnTo}` | like today's `department-login`; `returnTo` must be on the **requesting department's own host** |
| `POST /api/department/links` `{citizenId, assertion}` | verifies the other department's assertion (state, nonce, signature as today) and saves the link; on a duplicate, see 4.3 |
| `GET /api/department/journeys/{code}/readiness?citizenId=` | the connect-accounts view plus active-consent flag |
| `POST /api/department/consents/requests` `{citizenId, journeyCode}` | creates the consent request; returns request ID, wording (purpose text, categories, providers, validity), a nonce, expiry |
| `POST /api/department/consents` `{statement}` | verifies the statement (4.2) and grants the consent |
| `GET /api/department/applications?citizenId=`, `/{ref}`, `/{ref}/steps`, `/{ref}/issued-records` | only applications of journeys whose requester is the caller's department |

A department can act only as itself: the `department` claim must equal the requester of the journey, and the department of the assertion
for `resolve`. Every call is audited with the department client as actor.

### 4.2 Verifying a consent statement

Signature valid; header key's thumbprint **equals the department's pinned manifest key** (so the department must have been onboarded from a
signed manifest); `iss`/`dept_code` equal the caller's department; request exists, belongs to `citizen_id`, is open, and its purpose and
categories match; nonce matches and is single use; `confirmed_at` and `iat` recent; `jti` never seen. Then `ConsentService.grant` runs with
the statement's `jti` as the proof reference, and the statement is stored as evidence (new table `consent_evidence`, V206). Any failure is one
generic refusal, with the reason only logged.

### 4.3 Identity: one human, many departments

`resolve` uses a new proof provider `DEPT_HOME_LOGIN`: the same checks as `DEPT_ASSERTION` (signature against the department's published
keys, issuer, audience, department, person-ID type, short life, recent `auth_time`) but **no Samanvay-issued state**; replay protection is
instead a consumed `jti` (new table `identity_assertion_use`, V207). The shared checks are extracted from `DepartmentAssertionLinkProofProvider`
into one verifier used by both.

Problem: a citizen who starts at Education and later at Agriculture would be two Samanvay citizens, and linking Revenue the second time hits
the rule "one department person, one citizen". Resolution, **merge on proven collision**: when a link attempt collides with an existing link
on another citizen, and the current citizen was created implicitly, has no consent, no journey instance and only home links, then the person
has just proven control of both identities in one session. Move the current citizen's links to the existing citizen, mark the current one
`MERGED` (the status already exists), audit `CITIZEN_MERGED`, and return the surviving `citizenId`. Any other collision is refused as today.

### 4.4 Journeys

`POST /api/journeys/{code}/start` for a department caller: allowed when the journey's requester equals the caller's department (replacing the
"scoped for every data source" rule, which made the Education client unable to start a journey that fetches Revenue data). Consent, not
credentials, governs the fetching.

### 4.5 Manifest

`journeys[].portalUrl` (absolute, same host as the manifest, https outside dev); parsed, shown in the onboarding plan, stored on the journey,
and the same-host rule extended to it.

### 4.6 Citizen realm becomes optional

The citizen realm is active only when `samanvay.security.citizen.issuer-uri` is set. Unset: no citizen decoder, the startup check does not
demand it, `/ui/auth-config` lists only the staff realm. Deployments unset it and stop importing the citizen realm. Tests that set it keep
exercising the code. Deleting the inactive citizen API role is a later cleanup.

### 4.7 Removed from the middle-layer app

Static portals `scholarship`, `farmer`, `licence`, the citizen part of the React app, and the tests that only covered them. `/` becomes a
short staff landing page. Their end-to-end coverage moves to the new department-journey tests.

### 4.8 Staff: per-journey page (`/staff/admin/journeys/:code`, from Catalog)

Backend `GET /api/ops/journeys/{code}` (officer, admin): definition and status; for each required category the serving connector (ref,
version, status), its data source health and last trial; counts of instances by state over the last 7 days; the 20 most recent instances;
and the **journey log**: the step records (category, connector, outcome, latency, error) of those instances. Frontend page renders it with
Probe and Run-trial actions that already exist.

## 5. Department changes

Each of Revenue, DBT, Education, Agriculture gets: the kit dependency, `journeys.json`, manifest generated from it, name and DOB added to the
login assertion (Agriculture's schema gains `date_of_birth`), the portal's static assets at `/portal/`, `/` redirecting to the portal, `/login`
without `return_to` as the portal's own sign-in, and config for the Samanvay URL, token URL and client credentials.

`departments/pom.xml` becomes a reactor (`kit` + four departments); Dockerfiles build from the `departments/` context.

## 6. Portal front end

A second entry (`portal.html`) in the existing `frontend/` Vite project, reusing its UI parts, i18n (English and Marathi) and test setup, built
to `dist-portal` and copied into each department's `static/portal/` by `scripts/build-portal.sh` (git-ignored copies). One codebase; each
department supplies name, accent colour, and journeys from its BFF. Screens: Services, Journey (Apply, Your records, Consent, Submit),
Track, and the callback. Design: calm public-sector service, one accent per department, accessible, light and dark, no new font downloads.

## 7. Security notes

| Risk | Control |
|---|---|
| A department acts for another | claim checked on every `/api/department/**` call; matrix test |
| Forged or replayed login assertion | signature against published keys, short life, `auth_time`, consumed `jti` or state |
| Department claims consent that never happened | signed statement bound to a Samanvay-issued request and nonce, pinned key, evidence stored |
| Open redirect after the other department's login | `returnTo` must be on the requesting department's host |
| Wrong citizen merged | merge only for an implicit, empty citizen after a proven double login; audited |
| Browser holds a Samanvay token | it never does; only the department's server part does |
| Portal session theft | HttpOnly, Secure (when https), SameSite=Lax, 8 hours, HMAC |

## 8. Failure behaviour

Samanvay unreachable: the portal says so and offers retry; no half-recorded consent (the grant is one call). Assertion expired or login cancelled:
the readiness line stays "Not connected" with a plain reason. Wrong one-time code at consent: refused, citizen retries, nothing signed. Duplicate
or unmergeable link: a clear message naming the department. Journey start refused for a missing link or consent: the portal returns to the
step that fixes it.

## 9. Testing

- Samanvay: unit and integration tests per endpoint; a department-to-department security matrix; consent evidence, replay and nonce
  tests; merge-on-collision tests; realm-optional tests; per-journey page endpoint tests.
- Kit: against a fake Samanvay.
- Departments: portal BFF tests, manifest-from-journeys consistency test, `/login` direct sign-in tests.
- End to end: `DepartmentsEndToEndIT` extended to run all four journeys through the department services (resolve, link, consent, start, track).
- Front end: portal and staff journey page tests; type check, lint, build.
- Browser: each portal on the deployed demo.

## 10. Deployment and migration

Keycloak dev-mode regenerates client secrets on each import, so `scripts/provision-department-clients.py` sets the `dept-<code>` client
secrets (from the generator's state) through the Keycloak admin API after Keycloak starts. The generator adds the Samanvay URL and client
credentials to each department's env. The deployed Keycloak imports only the staff realm; `SAMANVAY_CITIZEN_ISSUER_URI` is unset. The five
servers are redeployed and each portal is checked in a browser.

## 11. Build order

1. Samanvay: assertion verifier extraction, `DEPT_HOME_LOGIN`, resolve, merge.
2. Samanvay: department link, consent request, signed consent and evidence, department reads, start scoping, security matrix.
3. Manifest `portalUrl`; citizen realm optional; remove middle-layer portals and citizen UI.
4. Kit module and a fake-Samanvay test harness.
5. Department changes (journey definitions, manifest from them, assertion claims, portal sign-in, BFF) in all four.
6. Portal front end.
7. Staff per-journey page (backend, then front end).
8. End-to-end test, deployment, browser verification, documentation.
