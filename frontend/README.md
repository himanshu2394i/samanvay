# Samanvay web app (`frontend/`)

A React single-page app for the Samanvay platform. It starts with the **citizen surface**:
browse services, connect department accounts, give consent, submit an application and track it,
all against the real REST API and the citizen Keycloak realm. Officer and admin surfaces are
not built yet; the code is laid out so they can be added beside the citizen one.

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

If you already had Keycloak running before this change, **re-import the citizen realm**: the
`samanvay-citizen-ui` client gained `http://localhost:5173` as an allowed redirect, web origin and
post-logout URI. Easiest is `docker compose down -v && docker compose up -d` (dev data only).

### How the dev server is wired

- `/api/*` and `/ui/*` are proxied to the API (`http://localhost:8080`; override with
  `SAMANVAY_API_URL`). The browser sees one origin, so the Spring app needs no CORS setup.
- **Keycloak is not proxied.** The API accepts a token only if its `iss` equals the issuer the API
  is configured with (`http://localhost:8180/realms/samanvay-citizen`), and compose pins Keycloak's
  public hostname to that URL. The browser therefore talks to Keycloak directly; the realm client
  allows this origin for redirects and CORS.
- The SPA learns the realm issuer and client id from the API's public `GET /ui/auth-config`
  (the same source the static portals use), so it embeds no IdP address. To bypass that, set
  `VITE_OIDC_AUTHORITY` and `VITE_OIDC_CLIENT_ID` (see `.env.example`).

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

Design notes that follow from the contract:

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
  auth/            OIDC config + UserManager, AuthProvider, RequireAuth   (shared by every surface)
  api/             ApiClient (bearer, JSON, problem+json), wire types, citizen endpoints
  ui/              Loading, ErrorNotice, Field, Badge, useAsync, error wording  (shared)
  surfaces/
    citizen/       layout, guards, pages/, lib/ (pure flow logic)   <- built
    # officer/     staff realm, its own layout + guards              <- later
    # admin/       ditto                                             <- later
  test/            fetch mock + render helpers
```

To add the officer surface: create `src/surfaces/officer/`, load the `staff` realm with
`loadOidcConfig('staff')`, add an officer API module next to `api/citizenApi.ts`, and mount it from
`App.tsx`.

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

- Officer and admin surfaces are not built (staff realm sign-in, review queue, bank reviews, audit).
- The static portals' DigiLocker document picker (`GET /api/connector/issued-documents`) is not
  reproduced; linking uses the proof kinds above directly.
- `DEPT_IDP` link proof is not offered (see above).
- English only; the static portals have a Marathi toggle.
