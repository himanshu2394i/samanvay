# Samanvay web app (`frontend/`)

A React single-page app for the Samanvay platform with two areas that share one build:

- the **citizen surface** (`#/...`): browse services, connect department accounts, give consent,
  submit an application and track it, on the citizen Keycloak realm;
- the **staff surfaces** (`#/staff/...`): the officer desk, ops dashboards, admin catalog and
  onboarding, and the reviewer queue, on the staff Keycloak realm.

Both use the real REST API. Each area signs in to its own realm and shows only what the roles in
the token allow (the API enforces the same roles on every call).

The existing static portals under `src/main/resources/static/` are untouched and keep working.

## Stack

| | |
|---|---|
| Build / dev server | Vite 8, TypeScript (strict), React 19 |
| Routing | React Router 7, **hash routing** (see [Serving in production](#serving-in-production)) |
| Auth | `oidc-client-ts`: Authorization Code + PKCE, public client, no secret |
| Styling | Plain CSS with design tokens (`src/index.css`); light/dark follows the OS |
| Tests | Vitest + Testing Library (jsdom), no Docker needed |
| Lint | ESLint 10 (flat config) + typescript-eslint + react-hooks rules |

Node 20.19+ or 22.12+ is required (Vite 8).

## Run it locally

You need the backend up first, with the `dev` (or `demo`) profile, because only those point the
API at the compose Keycloak:

```bash
# repo root
./mvnw -DskipTests package          # also builds the Keycloak email-code provider
docker compose up -d                 # Postgres + Keycloak :8180 + Mailpit :8025
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo   # API on :8080
```

Then the SPA:

```bash
cd frontend
npm ci
npm run dev                          # http://localhost:5173
```

Sign in as **`dev-citizen`**: enter the username, then read the one-time code in Mailpit at
<http://localhost:8025>. (Or register a new citizen in Keycloak's sign-in page; or use a passkey.
There are no passwords.)

For the staff surfaces open <http://localhost:5173/#/staff> and use **Staff sign in**. The dev staff
users are `dev-officer`, `dev-admin` and `dev-reviewer`; each has a temporary password
(`<username>-change-me`) that Keycloak makes you change, and an authenticator (TOTP) you enrol on
first sign-in. A passkey also works. Keycloak hosts that whole sign-in; the SPA never sees a password.

If you already had Keycloak running before these changes, **re-import the realms**: the
`samanvay-citizen-ui` (citizen realm) and `samanvay-staff-ui` (staff realm) clients gained
`http://localhost:5173` as an allowed redirect, web origin and post-logout URI. Easiest is
`docker compose down -v && docker compose up -d` (dev data only).

### How the dev server is wired

- `/api/*` and `/ui/*` are proxied to the API (`http://localhost:8080`; override with
  `SAMANVAY_API_URL`). The browser sees one origin, so the Spring app needs no CORS setup.
- **Keycloak is not proxied.** The API accepts a token only if its `iss` equals the issuer the API
  is configured with (`http://localhost:8180/realms/samanvay-citizen`), and compose pins Keycloak's
  public hostname to that URL. The browser therefore talks to Keycloak directly; the realm client
  allows this origin for redirects and CORS.
- The SPA learns each realm's issuer and client id from the API's public `GET /ui/auth-config`
  (the same source the static portals use), so it embeds no IdP address. To bypass that, set
  `VITE_OIDC_AUTHORITY` + `VITE_OIDC_CLIENT_ID` (citizen) and/or `VITE_STAFF_OIDC_AUTHORITY` +
  `VITE_STAFF_OIDC_CLIENT_ID` (staff); see `.env.example`.

## Scripts

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server on :5173 |
| `npm run build` | `tsc -b` (typecheck) then `vite build` into `dist/` |
| `npm run typecheck` | Typecheck only |
| `npm run lint` | ESLint |
| `npm test` | Vitest, single run (`npm run test:watch` to iterate) |

CI runs `npm ci`, `npm run build`, `npm run lint`, `npm test` in
[`.github/workflows/frontend.yml`](../.github/workflows/frontend.yml), on changes under `frontend/**`.
It is separate from the Java `./mvnw verify` job and needs no Docker.

## What the citizen surface does

Routes (hash-based, e.g. `http://localhost:5173/#/services`):

| Route | Page | Real endpoints |
|---|---|---|
| `/` | Landing, sign in | `GET /ui/auth-config` (at start-up) |
| `/services` | Browse services | `GET /api/catalog/journeys` |
| `/services/:code` | Service detail: what is fetched, from which department | `GET /api/catalog/journeys/{code}`, `GET /api/catalog/departments` |
| `/profile` | First-time details form, or saved details | `POST /api/identity/citizens` (idempotent per token subject), `GET /api/identity/citizens/{id}` |
| `/services/:code/apply` | Step 1, connect department accounts | `GET /api/identity/citizens/{id}/connect-accounts?journeyCode=`, `POST /api/identity/links` |
| | Step 2, consent (request, then grant) | `POST /api/consent/requests`, `POST /api/consent/requests/{id}/grant` |
| | Step 3, submit | `POST /api/journeys/{code}/start`, then `GET /api/applications?citizenId=` until the application appears |
| `/applications` | My applications; track by number | `GET /api/applications?citizenId=` |
| `/applications/:ref` | Status, department checks and the records fetched now (not stored by Samanvay); refreshes itself while in progress | `GET /api/applications/{ref}`, `GET /api/applications/{ref}/steps`, `GET /api/applications/{ref}/issued-records` |
| `/consents` | My consents; withdraw | `GET /api/consent/citizens/{id}`, `POST /api/consent/me/{id}/revoke` |

## What the staff surfaces do

Routes (hash-based, e.g. `http://localhost:5173/#/staff/officer/exceptions`). A route opens only
for the roles listed; anyone else sees an explanation page and the route makes no API call.

| Route | Roles | Page | Real endpoints |
|---|---|---|---|
| `/staff` | any staff role | Home: the consoles your roles may use | none |
| `/staff/officer/exceptions` | OFFICER | Exception queue; retry a stopped journey; show its step outcomes | `GET /api/journeys/exceptions`, `POST /api/journeys/instances/{id}/retry`, `GET /api/journeys/instances/{id}` |
| `/staff/officer/bank-reviews` | OFFICER | Bank-account reviews: passbook upload, ask for a document, approve, reject (reason required). Never shows a holder name | `GET /api/officer/bank-reviews`, `POST .../{id}/passbook` (multipart, 256 KB), `.../request-document`, `.../approve`, `.../reject` |
| `/staff/officer/applications` | OFFICER | Recent applications across citizens; filter by service and open/closed; SLA flags; open by number | `GET /api/applications?size=` |
| `/staff/officer/applications/:ref` | OFFICER | Case review: status, department checks, records received (live preview), this application's open exceptions with retry | `GET /api/applications/{ref}`, `.../steps`, `.../issued-records`, `GET /api/journeys/exceptions`, `POST /api/journeys/instances/{id}/retry` |
| `/staff/ops/metrics` | OFFICER, ADMIN | The four ops dashboards: SLA, exception queue, connector health, consent decisions | `GET /api/ops/metrics` |
| `/staff/ops/audit` | OFFICER, ADMIN | Audit ledger, read only: head, latest checkpoint, verify the chain, browse and filter entries | `GET /api/audit/head`, `/checkpoint`, `/verify`, `/entries` |
| `/staff/admin/catalog` | ADMIN | Journeys, departments, connectors | `GET /api/catalog/journeys`, `/departments`, `/connectors` |
| `/staff/admin/onboarding` | ADMIN | Six-step wizard: department, data source, draft connector, OpenAPI import and approve field matches, test, publish | `POST /api/catalog/departments`, `/data-sources`, `/connectors`, `/import/openapi`, `/mappings`, `/connectors/{ref}/test`, `/connectors/{ref}/publish`, `GET /api/catalog/schemas` |
| `/staff/reviewer/queue` | REVIEWER | Identity review queue: confirm or reject candidate account links | `GET /api/identity/review-queue`, `POST /api/identity/candidates/{id}/confirm`, `/reject` |

These follow `SecurityConfig` route by route. Things worth knowing:

- **Approving an application is a marked TODO.** No application approval endpoint exists on `main` at
  this commit (only bank-account reviews have approve/reject), and this app does not invent endpoints.
  The case page shows a disabled "Approve application" control that says so (`data-testid="approve-todo"`),
  and `ApplicationReviewPage.tsx` / `api/staffApi.ts` carry a `TODO(approve)`. Open PR #55 proposes
  `POST /api/journeys/instances/{instanceId}/approve` (OFFICER only, VERIFIED applications only, `instanceId`
  as on the application view). When it merges: add `approveApplication` to `staffApi.ts`, its case to
  `staffApi.test.ts` (which currently pins that no such call exists), and enable the button for VERIFIED
  applications.
- The importer only **previews**. Only the rows a person ticks become mapping rules, nothing is ticked
  by default, and publishing needs the passing test report from the step before.
- Officer-only data (applications, exceptions, bank reviews) is not offered to ADMIN, matching the API.
- The demo-profile-only controls of the static consoles (connector chaos kill/revive, audit tamper) are
  not reproduced; they exist only under `--spring.profiles.active=demo`.

Design notes that follow from the contract (citizen surface):

- **The API has no "who am I" endpoint.** A citizen token is bound to a citizen record by
  `POST /api/identity/citizens`, which returns the record id and is idempotent for the same token
  subject. The SPA remembers that id in `localStorage` per subject (a convenience only). On a fresh
  browser the details form shows again and submitting it returns the existing record.
- The consent **purpose code comes from the journey's own policy** (`policy.purpose`), never
  hard-coded, and the citizen sees the request (who, which records, why) before the grant call.
- Starting a journey returns only an instance id. The reference number is found by polling the
  citizen's application list for that instance. If it is slow, a retry only re-polls, so it can never
  file a second application.
- Department proofs offered: `DIGILOCKER` (sandbox) and `LOCAL_ID_OTP` (demo code `000000`).
  `DEPT_IDP` needs a separate brokered sign-in and is not offered in the SPA yet; the server still
  lists it and the SPA says which kinds it skips.
- Errors use the API's RFC 7807 bodies: `ApiError` carries `status`, `reason` and problem fields
  (for example `MISSING_DEPARTMENT_LINKS` names the departments to connect) and `ui/errors.ts` turns
  them into plain sentences.

## Auth

### Staff realm (officer, admin, reviewer)

- **Client:** the existing staff-realm public client `samanvay-staff-ui` (Authorization Code + PKCE
  S256, no secret, no direct grants, `samanvay-api` audience mapper, `department` claim scope). No new
  client was added and the API's `allowed-clients` are unchanged, so audience, `azp` and role checks are
  exactly as before. The only Keycloak change is in `keycloak/gen_realms.py`: that client also allows
  `http://localhost:5173` (redirect URI, web origin, post-logout URI), as the citizen client already
  does; the staff realm JSON was regenerated.
- **Sign-in** is the standard OIDC redirect to Keycloak, which hosts passkey or password + TOTP.
- **One realm per page load.** The realm is chosen at start-up: a `#/staff...` route uses the staff
  realm, anything else the citizen realm. The IdP redirect back carries no route, so the realm being
  signed in to is remembered in `sessionStorage` just before the redirect (`auth/realm.ts`); a lost
  marker fails closed to the citizen realm. Moving between the two areas reloads the page so the other
  realm's session is used. Each realm has its own `UserManager`, so tokens never mix.
- **Role gating** (`surfaces/staff/guards.tsx`): `RequireStaff` requires a signed-in session whose
  access token has at least one of `officer`, `reviewer`, `admin` in `realm_access.roles`; `RequireRole`
  then gates each route family (table above). A citizen token, or a staff-realm account with no staff
  role, gets "No staff access". The roles are read from the access token payload **without verifying it**
  (`auth/roles.ts`), which is only for deciding what to show: the API verifies the token and re-checks the
  role on every request, so this is never a security boundary.

### Citizen realm

- **Client:** the existing citizen-realm public client `samanvay-citizen-ui` (Authorization Code +
  PKCE S256, no secret, no direct grants, `samanvay-api` audience mapper). The SPA reuses it rather
  than adding a second client, because the API only accepts tokens whose `azp` is on its
  `allowed-clients` list (`application.yml`). The only Keycloak change is in `keycloak/gen_realms.py`:
  that client also allows `http://localhost:5173` (redirect URI, web origin, post-logout URI).
- **Tokens** live in `sessionStorage` (per tab). No refresh token is requested, so when the 5-minute
  access token expires the session ends with a notice and the citizen signs in again. A 401 from the
  API does the same.
- **Guard:** `RequireAuth` shows a sign-in prompt (no automatic redirect, so a failed sign-in cannot
  loop) that returns the citizen to the page they wanted. Routes that act on the citizen's own record
  additionally require the profile step.
- The redirect URI is the page's own URL without query or hash, so the same build works at the dev
  origin and when served by Spring under `/app/`.

## Source layout

```
src/
  auth/            OIDC config + UserManager, AuthProvider, RequireAuth, realm choice, token roles  (shared)
  api/             ApiClient (bearer, JSON/multipart, problem+json), wire types,
                   citizenApi.ts (citizen endpoints), staffApi.ts + staffTypes.ts (staff endpoints)
  ui/              Loading, ErrorNotice, Field, TextArea, Tile, Badge, useAsync, useAction, format  (shared)
  surfaces/
    citizen/       layout, guards, pages/, lib/ (pure flow logic)
    staff/         officer + admin + reviewer: layout, role guards, nav (single source for nav and home
                   cards), pages/, lib/ (status wording, onboarding helpers)
  test/            fetch mock, JWT helper, staff fixtures, render helpers
```

The two surfaces share only `auth/`, `api/` and `ui/`; nothing under `surfaces/citizen` imports from
`surfaces/staff` or the reverse. One `ApiProvider` exposes both typed APIs (`useCitizenApi`,
`useStaffApi`) over one client, whichever realm's token is signed in; the server decides what each token
may call.

## Serving in production

`npm run build` writes a static site to `frontend/dist/` (relative asset URLs, hash routing). The
intended production setup is for Spring to serve it as static resources, for example:

```bash
cd frontend && npm ci && npm run build
mkdir -p ../src/main/resources/static/app
cp -R dist/. ../src/main/resources/static/app/
# then open http://localhost:8080/app/index.html
```

This is deliberately **not** wired into the Maven build yet, and nothing under `static/app/` is
committed. Notes for when it is:

- Hash routing means deep links need no server-side fallback, and the OIDC redirect stays one fixed
  page. Spring does not serve a directory index, so link to `/app/index.html` or add a
  `forward:/app/index.html` view controller for `/app/` (as `CivicPortalsWeb` does for the portals).
- The `samanvay-citizen-ui` client already allows `http://localhost:8080/*`, which covers `/app/`.
  A real deployment must set its own origins in the realm.
- Being served from the API's origin, the SPA needs no CORS and `/ui/auth-config` and `/api/*`
  resolve as they do in dev.

## Known gaps

- Approving an application from the officer case page (no API endpoint yet, see above).
- Staff sessions end when the 5-minute access token expires (no refresh token), like citizen ones.
- After staff sign-out Keycloak returns to the page's bare URL, which opens the citizen landing page;
  use the "Staff sign in" link or `#/staff` to come back.
- The static portals' DigiLocker document picker (`GET /api/connector/issued-documents`) is not
  reproduced; linking uses the proof kinds above directly.
- `DEPT_IDP` link proof is not offered (see above).
- English only; the static portals have a Marathi toggle.
