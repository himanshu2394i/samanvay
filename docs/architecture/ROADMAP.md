# Roadmap — full product (post-Phase-3)

Status as of the `phase-3` line (three journeys run as configuration; Phase 2/3 hardening
landed). The architecture, module boundaries, schemas and business logic are complete and
tested (ArchUnit/modulith invariants enforced). What remains is replacing the
**mock / sandbox / in-process seams with production implementations**, plus feature
completeness and platform hardening.

This is the *full product* roadmap, not the SIH demo. The demo acceptance criteria live in
`docs/demo/PHASE4_RUNBOOK.md`; the items there marked "cuttable" are treated here as real
work.

## Out of scope (HLD §1.5 — do not start)

Aadhaar integration, blockchain, Kubernetes/HA, mobile apps, real payment rails, replacing
the citizen portals, and integrating real (production) department systems. "Real client"
work below means the real *transport* proven against fake/sandbox data, not live government
endpoints.

## Workstreams

Dependency order; each roughly unblocks the next. A is the critical path.

| ID | Workstream | Current reality on `main` | Done when |
|----|-----------|---------------------------|-----------|
| **A** | **Connector real clients + onboarding** | `RestAdapter` already has a real `DeadlineHttp` path; `SoapAdapter` echoes the envelope (no real POST); `SftpCsvAdapter` and `JdbcAdapter` are mock-only | Real REST/SOAP/SFTP transport each proven end-to-end against a fake-data endpoint, and onboarded as connector sources with a SIMULATOR→real mode switch and creds from `SecretStore` |
| ~~**B**~~ ✅ | **Flowable `WorkflowEngine`** | **Done.** `FlowableWorkflowEngine` behind the port, opt-in via `samanvay.workflow.engine=flowable`; durable timer/retry jobs (`RetryJobHandler`) persisted in the DB and re-entered on due date; `InProcessWorkflowEngine` remains the default fallback | ~~Flowable BPMN engine behind the existing `WorkflowEngine` port; timers/retries survive restart~~ — met (`FlowableWorkflowEngineTest.timerJobsAndInstancesSurviveARestart`) |
| **C** | **Keycloak department IdP brokering** | Realms + JWT auth exist; no department IdP brokers; DigiLocker is a sandbox mock | Department IdP brokering wired (to mock department realms is fine); demo/stub linking retired where the HLD expects OIDC |
| **D** | **React SPA (citizen / officer / admin)** | Thin static HTML only | React SPA against the existing APIs. *After C*, so login isn't rebuilt twice |
| ~~**E**~~ ✅ | **Observability dashboards (Micrometer)** | **Done.** Micrometer instrumentation (`shared/OpsMetrics`) across connector/consent/orchestration; `OpsMetricsService` + `GET /api/ops/metrics` (staff-secured) serve the connector-health (p50/p95), SLA, consent/access and exception-queue dashboards, rendered by the staff `MetricsPage` | ~~Connector-health (p50/p95), SLA, consent/access and exception-queue dashboards (HLD §10)~~ — met |
| **F** | **Notification channels** | In-app only | Email (Mailpit is already in compose) then SMS, with delivery retries |
| **G** | **Secrets/KMS + audit signing key** | `EnvSecretStore` is a stub (env/base64 or ephemeral key); audit signing key is ephemeral | `SecretStore` backed by Vault/KMS; audit signing key in KMS with rotation |
| **H** | **Payments/disbursement + semantic mapping + audit witness** | Disbursement core built (mock DBT, `ONCE_PER_PAYMENT` enforced); nothing publishes `APPROVED` yet; `MappingSuggestor` is lexical-only; audit external-witness publication is thin | Officer approval step that triggers the disbursement, semantic mapping pass (propose-only), stronger audit external witness (§9.4) |

## Notes on ordering

- **A first** — everything downstream that claims "real data" depends on the connector
  transport being real. Split into: real SOAP client, real SFTP client (+ embedded fake
  server), and REST wiring/onboarding.
- **G (secrets)** underpins any real `LIVE` source (credential-required boot check already
  exists), so it lands alongside A when a source needs a real credential.
- **C before D** so authentication is honest before the SPA is built on top of it.
- **F, H** are largely parallelizable once journeys are durable (B, now done).

## Already done (for reference)

Consent record + revocation, frequency enforcement (`ONCE` / `ONCE_PER_DOCUMENT_PER_APPLICATION`
/ `ONCE_PER_YEAR`), domicile data category (V191), deterministic name matcher, department
simulator + connector total-timeout, typed bank-check adapter, `DATA_ACCESSED` audit rows,
live-mode simulator-marker guard, bank-check review + officer passbook review (V192), and the
consent lifecycle jobs (EXPIRED-marker + retention purge — PR pending review).

Since the status line above, these workstreams also landed on `main`:

- **B — Flowable `WorkflowEngine`** (PR #46): opt-in Flowable 8 engine behind the port, durable
  timer/retry jobs that survive restart; `InProcessWorkflowEngine` stays the default.
- **D — React SPA** (citizen + officer/admin/reviewer surfaces) against the real APIs, later unified
  into one government operational layer with a shared design system (PR #60).
- **E — Observability dashboards** (PR #49): Micrometer instrumentation + connector-health/SLA/
  consent/exception dashboards, staff-secured, rendered by the SPA.
- **H (part)** — journeys consume real independent departments over all four protocols (PR #59); the
  **semantic mapping pass** (propose-only) and the **audit external witness** (§9.4, PR #61).

Remaining: **A** (some real transport still to onboard), **C** (department IdP brokering),
**F** (email/SMS channels), **G** (Vault/KMS-backed secrets), and the rest of **H** (officer approval
→ disbursement). **C/F/G and live DigiLocker need real infra, credentials or an external partnership.**
