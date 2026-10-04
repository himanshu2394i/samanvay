# Samanvay — demo video script (plain version)

**The one-line pitch:** Government services keep asking citizens for the same documents again and
again. Samanvay is the middle layer that fetches those documents *between* departments — with the
citizen's permission — so a service can be delivered without the paperwork run-around. This demo
shows a citizen using it, an operator running it, and a new department being plugged in live.

Record against the deployed site. Total ~5–6 min; each scene stands alone, so cut what you don't want.

---

## Sign-ins (all judge-friendly)

- **Staff / operator — Scenes 3, 4, 6, 7 (the SPA):** open any staff URL, e.g.
  `https://app.3.109.201.126.nip.io/app/#/staff/admin/onboarding`, click **Sign in** and use `dev-admin` (or
  `dev-officer` / `dev-reviewer`). The first sign-in asks for a new password and to enrol an authenticator
  code; do this once off-camera. The `#` matters. There is no one-click demo login.
- **Citizen — Scene 1 (static portals):** at a portal (e.g. `/scholarship/`) click **Sign in**, then **Register**
  (first name, last name, email, password), or sign in as `dev-citizen` / `dev-citizen-change-me`. To connect a
  department, use **Log in at …** and the demo account shown on that department's page.
- **Officer desk for the "department down" beat — Scene 2 (scholarship portal):** the portal's own
  **Officer desk** uses a simple demo login — ID `officer`, password `demo-2026`. No code.

> The **citizen** experience lives in the static portals at `/`; the **SPA** (`/app/#/staff/...`) is the
> staff/operator console. Keep each role in its own lane so it doesn't look like two apps of the same thing.

---

## Scene 1 — The problem, from the citizen's chair  (~50s)

- **Open:** `https://app.3.109.201.126.nip.io/` — the Maharashtra "Citizen services" page. Three
  separate government portals sit here: a scholarship, a business licence, a farmer subsidy.
- **Say:** "Three different departments, three websites. Normally the same citizen types the same
  income, caste, bank details into each one. Notice Samanvay isn't even on this screen — it works
  behind them."
- **Do:** open the **Scholarship** portal, start an application. Walk through: it asks to **connect
  your accounts** (Revenue, Education, bank), you **give consent**, you **submit**.
- **Say:** "I didn't upload a single document. With my permission, Samanvay went and fetched them
  from the departments that already hold them."
- **Do:** open **Track status** — you see actual certificate-style records coming back, not just the
  word "approved."

## Scene 2 — When a department's system is down  (~40s)

- **Open:** the scholarship portal's **Officer desk** (`/scholarship/#officer`, login `officer` /
  `demo-2026`). This is the one place with the "make a department fail" control.
- **Say:** "Real government systems go down. Here's what that looks like."
- **Do:** **mark the Revenue system unavailable**, refresh — the application flips to *needs attention*
  with a clear reason. Then **restore** it and hit **Retry**.
- **Say:** "Nothing was lost, nothing failed silently. It paused, told us exactly why, and picked up
  where it left off once Revenue was back."

## Scene 3 — Plug in a brand-new department, live  (~70s)  ⭐ the new bit

- **Open (admin):** `https://app.3.109.201.126.nip.io/app/#/staff/admin/onboarding`
- **Say:** "Adding a new department to a system like this is usually weeks of developer work. Watch."
- **Do:** in **"Discover a department from its URL"**, paste the department's web address and click
  **Discover**.
  > ⚠️ Use the department's **public** address (e.g. `https://dept.3.109.201.126.nip.io`), **not**
  > `localhost:8090` — the system refuses private addresses for safety. Test this once before rolling.
- **Screen:** it reads the department's published "menu" — the documents it holds **and the services
  it offers**. Each service lists the records it needs and which department provides each one.
- **Say:** "The department just publishes one standard file. Samanvay reads it and sets everything
  up — no hand-coding."
- **Do:** click **Register…**. One click creates the department, wires up a connector for each
  document, **and files each service as a draft.**

## Scene 4 — "Is it ready?" is answered honestly  (~40s)

- **Open:** `https://app.3.109.201.126.nip.io/app/#/staff/admin/journeys` — the **Journeys** table.
- **Screen:** the new **Readiness** column. Existing services say **Live**; the one you just added
  says **Pending — needs …**.
- **Say:** "This is the honest part. A service can't go live until the plumbing to fetch every record
  it needs is *actually* connected. The system works that out itself and won't let you publish
  early. When it's genuinely ready, a human — not the machine — flips it live."

## Scene 5 — These aren't fake buttons  (~40s)

- **Open:** the **Departments** page (`/app/#/staff/admin/departments`) and a journey's status page (`/app/#/staff/admin/journeys/<code>`), which list each document's serving connector; the **Central schema** page (`/app/#/staff/admin/schemas`) shows what the fields map onto.
- **Say:** "Behind the scenes this really talks to four completely different kinds of government
  systems: a modern REST web API, an old SOAP service, a file drop over SFTP, and a direct database
  query. Same middle layer, four real technologies."
- **Do (optional, strongest proof):** go back and run a **scholarship** application end-to-end — that
  one really calls the live REST + SOAP department services — or a **business-NOC**, which really
  pulls a file over SFTP and runs a database query.
- **Note for you:** the old placeholder connectors (host `mock.samanvay.test`) are not listed on the Departments page,
  which shows only onboarded departments; do not open them on camera.

## Scene 6 — You can't quietly change the record  (~40s)

- **Open:** `https://app.3.109.201.126.nip.io/app/#/staff/ops/audit` — the audit ledger.
- **Do:** click **Verify** — the whole chain checks out. Then use the **tamper** demo button (it exists only when the demo
  server runs with `samanvay.demo.tamper-endpoints=true`; see `deploy/README.md`) to alter
  one past entry, and **Verify** again — it now **fails** and points at the exact broken link.
- **Say:** "Every access is written to a tamper-evident chain. Change one old record and the whole
  thing screams. And note — the citizen's actual data was never *stored* here; it was fetched, used,
  and let go."

## Scene 7 — The operator's live dashboard  (~30s)

- **Open:** `https://app.3.109.201.126.nip.io/app/#/staff/ops/metrics`
- **Say:** "And the people running it see it live — how each department connection is performing,
  which cases are close to breaching their deadline, consent grants and denials — all in one place."

## Close (~15s)

- **Say:** "We didn't build three government services. We built the interoperability layer that lets
  every service reuse what departments already hold — with consent, with a full audit trail, and
  with new departments plugging in from a single file."

---

### If you want to show even more (SPA — just click the demo button)
- **Officer console** (`…/app/#/staff/officer/exceptions`, **Demo: Officer**): exception queue,
  bank-account review, the full applications list, and a "Citizen-360" view of one person.
- **Reviewer** (`…/app/#/staff/reviewer/queue`, **Demo: Reviewer**): the identity-matching review queue.

### The things that can trip you on camera
1. **Staff URLs need the `#`** — `.../app/#/staff/...`, not `.../app/staff/...`.
2. **Discover needs a public department URL** — `localhost` is refused. Test it before recording.
3. **The one-click demo buttons need the redeploy** — until then the SPA sign-in shows only the normal
   Keycloak sign-in (password + code).
