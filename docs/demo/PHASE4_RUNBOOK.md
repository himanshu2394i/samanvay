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

**Judge path starts on the Scholarship Portal**, not on Samanvay operations.

Open `http://localhost:8080/` (three equal citizen-service cards). Judge path: Scholarship Portal at `/scholarship/`. Licence at `/licence/` (`BUSINESS_NOC`). Farmer at `/farmer/` (`FARMER_SUBSIDY` — same apply/officer pattern, no journey picker). Apply → connect department accounts (labelled DigiLocker sandbox or OTP demo — not live SSO) → consent in plain language → submit. Track status in everyday words.

Then **Officer desk** (`/scholarship/#officer`): labelled demonstration login (`officer` / `demo-2026` — not SSO) → review which department records arrived → optionally **Mark Revenue records unavailable**, restore, then **Retry**. Do not show `PARTIALLY_VERIFIED` or instance UUIDs to judges.

Optional 60-second Samanvay cutaway after that: `/demo.html` (operations script) → `/audit.html` and `/schemes.html` (farmer subsidy as configuration). Do **not** open Command/Caller first. Those stay under **Staff tools**.

Default boot does **not** activate `demo`. Without it, `POST /api/audit/demo/tamper/{seq}` and `POST /api/connector/chaos/**` are unregistered (404). **Officer Revenue unavailable/restore and Audit tamper need `--spring.profiles.active=demo`.**

| Beat | Where | How it is proven |
|---|---|---|
| 1 | Three portals / same form | Problem statement. **Start at `/scholarship/`** — citizen scholarship apply on a government portal. Samanvay is not on screen. |
| 2 | Connect department accounts | Portal **Connect accounts** checklist. Consent grant still uses stub `X-Auth-Jti`. **Not live Keycloak SSO.** |
| 3 | Scholarship fan-out | Portal submit starts `POST_MATRIC_SCHOLARSHIP`. |
| 4 | Revenue unavailable mid-flight | **Officer desk**: Mark Revenue records unavailable → application needs action → Restore → Retry. Optional staff cutaway: `/ops.html`. |
| 5 | Consent revoke | `POST /api/consent/{id}/revoke` → next authorize denied |
| 6 | Audit verifier | `/audit.html`: Verify range → Tamper head → fail |
| 7 | Onboard from spec | `/onboard.html` (staff tool, not judge path) |
| 8 | Journey 3 is configuration | `/schemes.html` live catalog list: Farmer subsidy as configuration — no new Java (`FarmerSubsidyJourneyIT`) |

### Phase-UI surfaces

| URL | Surface |
|---|---|
| `/` | Citizen services directory — three skins |
| `/scholarship/` | **Judge entry** — Scholarship Portal (gov service). Calls identity, consent, journeys, tracking. Not the control plane. |
| `/licence/` | Business licence / NOC skin — `BUSINESS_NOC` |
| `/farmer/` | Farmer subsidy skin — `FARMER_SUBSIDY` (no journey picker) |
| `/scholarship/#officer` | Officer desk — demonstration login, department records, Retry |
| `/demo.html` | Optional operations cutaway — judge script; not the entry |
| `/schemes.html` | Published catalog journeys — farmer subsidy as configuration |
| `/journey.html?ref=…` | Journey timeline cutaway |
| `/ops.html` | Exceptions cutaway (staff). Prefer Officer desk for beat 4 |
| `/caller.html` | Staff tool: generic external caller, not the judge path |
| `/audit.html` | Audit ledger cutaway — verify, entries, demo tamper |
| `/command.html` | Ops console (former Command Center) — live catalog, applications, exceptions, ledger |
| `/onboard.html` | Supporting OpenAPI import tool (side tool) |

## Known gaps

- No React SPA (HLD §11). Thin static HTML Phase-UI against existing APIs.
- Keycloak identity brokering is stubbed (`X-Auth-Jti` for consent). Department linking uses labeled demo/sandbox proof providers, not a fake live SSO hop.
- Flowable Boot 4 remains optional behind `WorkflowEngine`.
- Semantic/model mapping pass is omitted; suggestions are lexical only (HLD §8.2).
- Connector-health p50/p95 Micrometer charts are not built; SLA is due-at vs now on the ops table.
- `/internal/audit/ping` stays gone.

## Tag

Suggested git tag: `phase-4`.
