# Phase 4 demo rehearsal

The HLD has **no Phase 5**. Phase 4 is the last build-plan phase (HLD §14). This runbook is how the eight-minute demo (§16) is walked **three times without babysitting**.

## Unattended proof

```bash
./scripts/demo-rehearsal.sh
```

That runs `DemoRehearsalIT` (`@RepeatedTest(3)`): three journeys, Revenue-killed mid-flight → `PARTIALLY_VERIFIED` + exception + retry, consent revoke → next authorize denied inside the 60s grant TTL, audit verify → live tamper → verify fail, OpenAPI preview with **unapproved** suggestions.

Same class is in `./mvnw verify`.

## Manual laptop walk (optional)

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

**Judge path starts on the Scholarship Portal**, not on Samanvay Ops/console.

Open `http://localhost:8080/scholarship/` (Government of Maharashtra — Scholarship Services). Apply → connect the three department accounts (Revenue / Education / DBT; labelled DigiLocker mock or local-ID OTP — not live SSO) → consent in plain language → submit. Status uses tracking, with human wording (submitted / in progress / needs action / completed). Officer desk is a thin list of applications.

Samanvay is the middle layer the portal calls. Optional cutaways after that: Phase-UI Demo `http://localhost:8080/` → Journey / Incident (`/ops.html`) / Audit. Do **not** open the demo on Ops/Command first. `/caller.html` remains the generic external-caller stand-in (any catalog journey), not the citizen product.

Default boot does **not** activate `demo`. Without it, `POST /api/audit/demo/tamper/{seq}` and `POST /api/connector/chaos/**` are unregistered (404). **Incident kill/revive and Audit tamper need `--spring.profiles.active=demo`.**

| Beat | Where | How it is proven |
|---|---|---|
| 1 | Three portals / same form | Problem statement. **Start at `/scholarship/`** — citizen scholarship apply on a government portal. Samanvay is not on screen. |
| 2 | Link department IDs + SSO | Portal **Connect accounts** checklist (Revenue / Education / DBT) calls `/api/identity/links`. Proof is a labelled DigiLocker-shaped mock or local-ID OTP. **Keycloak full brokering stays stubbed** (`X-Auth-Jti`). |
| 3 | Scholarship fan-out | Portal submit starts `POST_MATRIC_SCHOLARSHIP` (`ScholarshipPortalIT` + `ScholarshipJourneyIT`). Optional: `/caller.html` still starts any catalog journey. |
| 4 | Revenue killed mid-flight | Incident (`/ops.html`): Kill `revenue-rest-mock` → start a journey → status `PARTIALLY_VERIFIED`, Exceptions → Revive → Retry pending |
| 5 | Consent revoke | `POST /api/consent/{id}/revoke` → next `AccessAuthority.authorize` is `DENIED` (`GRANT_DENIED` on Incident denials) |
| 6 | Audit verifier | `/audit.html`: Verify range (green) → Tamper head → spectacular fail. Tamper uses the **migrate** role; `samanvay_app` still cannot `UPDATE` |
| 7 | Onboard from spec | `/onboard.html`: Suggest mappings (advisory) → check boxes → Save approved only |
| 8 | Journey 3 is configuration | `FarmerSubsidyJourneyIT` + `git log` / Phase 3 tag: farmer subsidy landed as catalog/BPMN/policy, not Java |

### Phase-UI surfaces

| URL | Surface |
|---|---|
| `/scholarship/` | **Judge entry** — Scholarship Portal (gov service). Calls identity, consent, journeys, tracking. Not the control plane. |
| `/` | Control-plane demo entry — interoperability middle-layer framing; links to portal + Caller |
| `/caller.html` | External caller demo (generic catalog start, not the citizen product). After start: open Journey / Incident / Audit |
| `/journey.html?ref=…` | Journey timeline cutaway (Identity → Consent → departments → recovery) |
| `/ops.html` | Incident cutaway — chaos (demo), exceptions, retry, denials |
| `/audit.html` | Audit ledger cutaway — verify, entries, demo tamper |
| `/command.html` | Ops console (former Command Center) — live catalog, applications, exceptions, ledger |
| `/onboard.html` | Supporting OpenAPI import tool (side tool) |

## Known gaps

- No React SPA (HLD §11). Thin static HTML Phase-UI against existing APIs.
- Keycloak identity brokering is stubbed, not a live IdP hop.
- Flowable Boot 4 remains optional behind `WorkflowEngine`.
- Semantic/model mapping pass is omitted; suggestions are lexical only (HLD §8.2).
- Connector-health p50/p95 Micrometer charts are not built; SLA is due-at vs now on the ops table.
- `/internal/audit/ping` stays gone.

## Tag

Suggested git tag: `phase-4`.
