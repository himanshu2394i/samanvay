# Handoff — Frontend redesign (government operational layer)

_For a fresh agent with no access to the conversation this came from. Everything you need is here or
linked. Do not change backend APIs; consume them as they are._

## The job

Rethink and rebuild the **frontend** of Samanvay as a **government operational layer** — not a
technical dashboard. A non-technical government user (a clerk, an officer) and an ordinary citizen
must be able to use it without training. Keep a **calm, official, accessible, government identity**.
Remake the **dashboard, all pages, and the end-to-end flow** so they read as one coherent product.

It is technical underneath, but the surface must not feel technical. Name things by what a person
does ("Review applications", "Connect your records", "Approve"), never by how the system is built
("connector runtime", "JDBC source", "BPMN instance").

## Use the `frontend-design` skill (install it first)

This work is meant to be done with the Claude Code **`frontend-design`** skill (design-led, anti-slop).
Install it before starting:

```
# In the Claude Code session, add the official marketplace (if not already added) and install:
/plugin marketplace add anthropics/claude-plugins-official
/plugin install frontend-design@claude-plugins-official
```

Then invoke it with the slash command **`/frontend-design:frontend-design`** followed by the brief
(you can paste the "Design direction" section below as the brief). If your harness cannot run
`/plugin`, the plugin is a Git marketplace — `anthropics/claude-plugins-official`, plugin
`frontend-design` — so you can also add it through the `/plugin` UI, or just follow the design
process described in that skill: **brainstorm a direction → define color/type/layout/signature tokens
→ critique against the brief → build → critique again**, spending boldness on one signature element.

Also consider the **`superpowers:brainstorming`** skill (same marketplace, plugin `superpowers`) to
pin requirements before building, and `superpowers:writing-plans` to break the rebuild into tasks.

## What Samanvay is (domain context)

Samanvay is a **middle interoperability layer** between citizen-facing government services and the
departments that hold records. Instead of a citizen uploading the same certificates to every scheme,
a service (e.g. a scholarship) asks Samanvay, with the citizen's **consent**, to **fetch** the needed
records straight from the source departments (Revenue, Education, DBT, Municipal, Pollution, …) over
their real protocols, then runs the eligibility **journey** and tracks status. Every access is
consented and **audited**.

Three citizen journeys exist today: **Post-matric scholarship**, **Business licence / NOC**, and
**Farmer subsidy**. Officers review and retry; operators onboard departments and watch health.

Read for depth: [`docs/architecture/HLD.md`](../architecture/HLD.md),
[`docs/architecture/hld/`](../architecture/hld/), [`docs/demo/JUDGE_SCRIPT.md`](../demo/JUDGE_SCRIPT.md),
[`docs/demo/PHASE4_RUNBOOK.md`](../demo/PHASE4_RUNBOOK.md), [`README.md`](../../README.md).

## Current frontend (what you are replacing / unifying)

There are **two** frontends today, which is the core problem — unify them into one coherent operational layer:

1. **React SPA** — [`frontend/`](../../frontend) (Vite + React + TypeScript, ~49 `.tsx`). Officer/admin
   and citizen surfaces (`frontend/src/surfaces`, `frontend/src/ui`, `frontend/src/api`, `frontend/src/auth`).
2. **Static HTML "Phase-UI"** — [`src/main/resources/static/`](../../src/main/resources/static) (14 pages):
   - Entry: `index.html` (citizen services directory)
   - Citizen portals: `scholarship/`, `licence/`, `farmer/`
   - Operator/officer consoles: `command.html`, `ops.html`, `audit.html`, `onboard.html`,
     `metrics.html`, `caller.html`, `officer/bank-reviews.html`, `schemes.html`, `journey.html`, `demo.html`

**Recommendation:** consolidate on the **React SPA** (`frontend/`) as the single frontend, retiring
the static HTML pages by rebuilding their function as SPA routes. Confirm the SPA already covers auth
and the API client before deciding; if the SPA is too thin, that is fine — treat this as a rebuild.

## Users and their jobs (design each surface around a real task)

- **Citizen** — start an application, connect their department records (one consent screen, not a
  document upload list), read plain-language status (submitted / in progress / needs action / done).
- **Officer** — a worklist of applications, see which department fetch failed and **retry**, approve
  or reject, review a bank passbook when required.
- **Operator / admin** — a home dashboard (what is flowing, what needs attention), **onboard** a new
  department/connector, watch connector health & SLA, browse the **audit** ledger and verify it.

## The backend contract (consume; do not change)

Auth: staff & citizen realms (Keycloak). Local dev has a **dev "paste token" sign-in** bar
(`/shared/auth-dev.js`, dev/demo profiles only). Bearer JWT on `/api/**`.

Key routes (from the current pages — treat as the contract):

```
/api/identity/citizens            /api/identity/links            /api/identity/review-queue
/api/consent/requests             /api/consent/citizens/{id}
/api/journeys/{code}/start        /api/journeys/instances/{id}/retry   /api/journeys/exceptions
/api/applications                 (tracking: status, steps, reference no.)
/api/catalog/journeys | departments | data-sources | connectors | mappings | import/openapi
/api/officer/bank-reviews         /api/ops/metrics
/api/audit/head | entries | verify | checkpoint | demo/tamper/{seq}
/api/connector/chaos/{dataSourceCode}   (dev/demo only: kill/revive a source)
```

Look at the existing static pages' `fetch()` calls and `frontend/src/api` for exact request/response
shapes before wiring — they are the source of truth for payloads.

## Design direction (a starting point — make it your own, then justify it)

This is a design-led build. Do a proper direction pass before coding:

1. **Pin the subject**: a state-government operational console + citizen service. Audience: government
   clerks/officers (desktop, possibly low-end screens) and citizens (often mobile).
2. **Tokens** — decide and write down: a restrained **palette** (4–6 named hex; official and calm, not
   a dark "tech" theme, not the generic cream-serif-terracotta AI default), a **type** pairing (a
   clear, legible pairing that reads as official and is comfortable at small sizes and in long lists),
   a **layout** system (dense but calm worklists and forms; generous for citizen screens), and one
   **signature** element that ties it together (e.g. a consistent status language / stepper that every
   surface shares).
3. **Government identity, honestly**: it should look like a real government service, but it is a
   prototype — do **not** impersonate a specific real department/agency (no real seals/logos/domains
   presented as genuine). A clearly-labelled generic "Government of Maharashtra (demo)" style is fine.
4. **Plain language everywhere** (see the writing guidance below).

### Non-negotiable quality floor
- Fully **responsive** down to mobile (citizen flows are mobile-first).
- **Accessibility**: visible keyboard focus, semantic landmarks, adequate contrast (target WCAG AA),
  `prefers-reduced-motion` respected, forms with real labels and error text.
- **Copy**: active voice, sentence case, name actions by outcome ("Approve", "Retry", "Connect
  records"); errors say what happened and how to fix it; empty states invite the next action.
- Motion is subtle and purposeful, never decorative filler.

## Deliverables

1. A short **design direction** doc (palette, type, layout, signature — with the one risk you took and
   why) committed under `docs/` before large-scale coding.
2. The rebuilt **frontend**: a **home/dashboard**, the **officer worklist + application detail + retry
   + approve/reject + bank review**, the **operator onboarding + health/SLA + audit ledger**, and the
   **citizen flow** (start → connect records → consent → submit → track), all as one coherent product.
3. It **runs against the existing backend** unchanged, on the demo profile.

## How to run and verify

Backend (serves APIs on `:8080`):
```bash
docker compose up -d                 # postgres, keycloak (:8180), mailpit, department services
./mvnw -DskipTests package           # builds the keycloak email-otp provider the compose keycloak needs
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```
Frontend (React SPA):
```bash
cd frontend && npm install && npm run dev     # Vite dev server; proxies/points at :8080
```
CI has a **`frontend`** job (npm build/lint/test) plus the Java `build` job — both must stay green.
Verify each flow end-to-end against the running backend: citizen apply → officer retry/approve →
operator audit-verify. Walk it as a non-technical user would.

## Constraints & guardrails

- **Do not change backend APIs, schemas, or migrations.** This is a frontend rebuild.
- Keep the app buildable: the SPA lives in `frontend/`; if you retire static pages, make sure nothing
  server-side hard-depends on them (search `src/main/java` for `static/…` and the welcome page).
- Branch off `main`, open a PR, keep both CI jobs green; do not merge without the maintainer.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## Known context / caveats

- A separate in-flight backend change makes the three journeys fetch from **real** department services
  instead of the in-process mock. It does **not** change the frontend contract (same `/api` routes and
  payloads), so it does not block this work.
- Local citizen login uses an emailed one-time code (read it in **Mailpit** at `:8025`) or the dev
  paste-token bar; officers use password + TOTP. For a frontend build, the dev paste-token path is the
  quickest way to get an authenticated session.
- There is a live demo deployment of the current build (single EC2 + docker compose); the redesign
  will eventually deploy the same way.

## Suggested first steps

1. Stand up the backend (above) and click through the current static pages + the React SPA to learn
   the real flows and payloads.
2. Read `frontend/src/api` and a couple of static pages' `fetch` calls to capture the API shapes.
3. Write the design direction doc; get it sanity-checked; then rebuild surface by surface, starting
   with the **home/dashboard** and the **citizen apply→consent→track** flow.
