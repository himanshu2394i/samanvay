# Samanvay — What the platform does (team overview)

_A plain-language tour of the whole system for the team: what problem it solves, how it's
built, what each part does, and where we're headed. For deep design, see the HLD/LLD under
`docs/architecture/`; for the remaining build plan, see `docs/architecture/ROADMAP.md`._

---

## 1. The problem

A citizen applying for a scholarship, a business licence, or a farmer subsidy has to prove
the **same facts** (income, caste, domicile, land, bank account) to **every department**,
re-uploading the same documents each time. Departments can't safely fetch what another
department already holds, and the citizen has no control over who sees what.

**Samanvay** is a government data-**interoperability** platform (SIH26129). It lets one
department fetch verified data from another **only with the citizen's consent**, over a
common core, with every access written to a tamper-evident audit trail. Fill the form once;
the platform gathers the rest, with the citizen's permission, and shows them exactly what was
accessed.

## 2. The big idea in one flow

1. A citizen starts a **journey** (e.g. scholarship) in a department portal.
2. They **link** their department accounts (identity linking: the citizen logs in at the department
   itself; a labelled OTP demo covers a department with no login yet).
3. The platform asks for **consent** for a specific purpose and data categories; the citizen
   sees the request and **grants** it. The grant is a signed, time-boxed artifact.
4. **Orchestration** fans out to the needed **connectors**, which fetch each data item from
   the owning department (REST / SOAP / SFTP / JDBC), bounded by a deadline.
5. Results are mapped to a schema, the application is assembled, and the citizen can **track**
   its status. If a source is down, it goes to an **exception/officer** queue for retry.
6. Every check, grant, fetch and refusal is a chained **audit** entry that can be verified and
   would visibly break if tampered with. The citizen can **revoke** consent at any time; the
   next fetch is then denied.

The same core runs **three journeys** with no per-journey Java code — journeys are
**configuration** (catalog + BPMN + policy): **scholarship**, **business NOC**, **farmer
subsidy**.

## 3. Architecture at a glance

Spring Modulith (one deployable, enforced module boundaries via ArchUnit), split into a
**control plane** (decides who may access what) and a **data plane** (does the fetching and
tracking), over a cross-cutting **audit** spine. Acyclic by construction.

| Module | Plane | What it does |
|--------|-------|--------------|
| **audit** | cross-cutting | Append-only, hash-chained, signed audit log; checkpoints; verifier. The security spine — every write path records to it. |
| **catalog** | control | The registry of journeys, purposes, data categories, connector & schema definitions, and mappings. Also the OpenAPI onboarding importer. |
| **identity** | control | Citizen records and **account linking** to departments, with pluggable proof providers (DigiLocker sandbox, local ID + OTP, brokered department IdP); resolution + reviewer queue. |
| **registry** | control | Discovery: which department holds a pointer to which citizen's data, freshness and clearance policies. |
| **consent** (+ AccessAuthority) | control | Consent requests, signed grants, revocation, **frequency** rules, expiry, retention; the `authorize()` gate every fetch passes through. |
| **connector** | data | Talks to departments over REST / SOAP / SFTP / JDBC behind one adapter port, with total-deadline HTTP, resilience (retry/circuit-breaker), simulator-vs-live modes, and bank-account checks. |
| **orchestration** | data | Runs the journey (WorkflowEngine port; in-process by default, **Flowable** optional), parallel fan-out, degraded mode, exception queue, officer review. |
| **payments** | data | Mock DBT: issues a disbursement with instalment ids when an application is approved (idempotent, audited). No real payment rail. |
| **tracking** | data | The citizen-facing status projection of an application's progress. |
| **notifications** | data | Tells recipients what happened — in-app, and **email** (SMS stubbed). |
| **shared** | cross-cutting | Security (Keycloak JWT, roles), SecretStore, name matching, mapping transforms. |

## 4. What each capability actually does

### Consent & access control (the heart)
- **Consent records + revocation:** a citizen grants a purpose over specific data categories;
  the grant is an Ed25519-**signed**, versioned, time-boxed artifact. Revoking bumps the
  version so old grants stop verifying, and the next fetch is denied with a plain-language
  reason.
- **Frequency enforcement:** a purpose can be `ONCE`, `ONCE_PER_DOCUMENT_PER_APPLICATION`,
  `ONCE_PER_YEAR`, or `ONCE_PER_PAYMENT` — the platform counts checks per the right scope and
  refuses extra ones. `ONCE_PER_PAYMENT` is scoped by a keyed HMAC of the instalment id issued
  by the `payments` disbursement flow (mock DBT, no real rail).
- **Expiry & retention (scheduled jobs):** an ACTIVE consent past its validity is proactively
  marked EXPIRED; ended consents are purged after a 7-year retention window (audit rows are
  never purged).
- **Data categories** include income, caste, marks, **domicile**, bank-account checks, etc.

### Identity & authentication
- **Account linking** with verifiable proofs: the department's own **login assertion** (signed, one-time,
  docs/contracts/login-assertion.md), a labelled local ID + OTP demo for a department without a login, and a
  **brokered department login** — a citizen can sign in through a
  (mock) department IdP via Keycloak OIDC brokering, and that login becomes a verified link.
- **Auth plane:** Keycloak realms (citizen: email + password or passkey; staff: passkey or
  password + TOTP), JWTs required on the API with audience/azp checks and role-based access
  (citizen / officer / reviewer / admin / department client).

### Connectors (talking to departments)
- **Four protocols** behind one `ProtocolAdapter` port: **REST** and **SOAP** (real
  HTTP with a single total-deadline that cancels a hung/trickling department), **SFTP**
  (real SSH/SFTP CSV pull with host-key pinning and a size cap), and **JDBC**.
- **Simulator vs LIVE modes:** a source can run against the built-in department simulator or
  a real endpoint; a LIVE source must have its credential in the SecretStore or the app
  refuses to boot, and a LIVE source that returns the simulator marker is refused and alarmed.
- **Onboarded sandbox sources** demonstrate the real REST/SOAP/SFTP transports end-to-end
  against fake endpoints.
- **Resilience:** retry, circuit-breaker, bulkhead per source; a killable **chaos** switch
  for drills; typed bank-account checks with officer review routing and passbook upload.

### Orchestration & journeys
- The **WorkflowEngine** port drives a journey; the default is an in-process engine, with
  **Flowable** (BPMN, durable timers that survive restart) available opt-in behind the same
  port.
- **Parallel fan-out** across sources, **degraded mode** when a source is down, an
  **exception queue**, and an **officer desk** for retries and bank-account review.

### Audit (trust)
- Every entry is **hash-chained** to the previous one and signed; a tamper anywhere makes
  verification fail visibly. Periodic checkpoints; a verifier surface; canary checks that fail
  the build if a real holder name could leak.

### Tracking, notifications, observability
- **Tracking:** the citizen sees each step of their application move.
- **Notifications:** in-app always; **email** via SMTP (Mailpit in dev); SMS is a stub for a
  future gateway. Recipients subscribe per event/channel.
- **Observability:** Micrometer metrics for connector latency (p50/p95), consent outcomes,
  and the exception queue, surfaced on **staff-only dashboards** (connector health, SLA,
  consent, exceptions) — no metrics endpoint is exposed publicly.

### Surfaces (UI)
- **Static portals** (served by the app): citizen services directory, the three department
  portals, the officer desk, and staff consoles (ops, audit ledger, catalog, onboarding
  importer, metrics).
- A **React SPA** (Vite + TypeScript) for the **citizen** experience, wired to the real APIs
  and Keycloak OIDC login. (Officer/admin SPA surfaces are planned.)

## 5. How we know it works
- ~**295** automated tests (unit + Testcontainers integration for Postgres/Keycloak/SFTP/SOAP
  simulators), plus **ArchUnit/Modulith** rules that fail the build on a boundary violation
  or any per-journey hardcoding.
- A **demo rehearsal** harness runs the three journeys, a mid-flight department outage +
  retry, a consent revoke, and a live audit tamper→verify-fail, repeatably.
- CI runs `./mvnw verify` on every PR; the citizen SPA has its own frontend CI job.

## 6. Where we are, and the final state we're heading to

**Done / in place now:** the full architecture and business logic across all 13 modules; all
three journeys as configuration; consent (records, frequency, revocation, expiry, retention);
identity linking incl. Keycloak department brokering; real REST/SOAP/SFTP connector transports
+ onboarding; Flowable engine (opt-in); observability dashboards; email notifications; the
citizen React SPA; the tamper-evident audit spine.

**Remaining to reach the full-product final state** (see `ROADMAP.md`):
- **G — Secrets/KMS:** move the SecretStore and the audit signing key off dev stubs onto
  Vault/KMS with rotation (foundational for running any source truly LIVE).
- **H — Payments/disbursement flow:** done end to end — the officer approval step publishes
  `APPROVED` (`ApplicationApprovalService`), which the `payments` module turns into an idempotent
  mock-DBT disbursement; the **semantic** onboarding mapping pass (propose-only, on top of the
  lexical one) and a stronger audit **external witness** have also landed.
- **Flowable journey wiring:** give the shipped BPMNs real service tasks + a deploy step so
  `flowable` mode can run the actual journeys (the engine + durable timers already work).
- **Production hardening:** SMS gateway, deployment (beyond docker-compose), and the
  compliance surface (DPDP-Act data-principal rights, breach reporting).

**The final state:** a citizen fills one form and consents; the platform, governed by that
consent and recorded in an unforgeable audit trail, gathers verified data from the relevant
departments over real protocols, runs the journey on a durable workflow engine, keeps the
citizen informed, and lets staff watch the system's health — with new departments and journeys
added as **configuration**, not code.

**Deliberately out of scope** (per HLD §1.5): Aadhaar integration, blockchain, Kubernetes/HA,
mobile apps, real payment rails, replacing the citizen portals, and integrating real
production department systems. "Real" here means the real transports and flows proven against
sandbox/fake data.
