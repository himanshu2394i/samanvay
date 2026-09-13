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
./mvnw spring-boot:run
```

| Beat | Where | How it is proven |
|---|---|---|
| 1 | Three portals / same form | Problem statement; not automated. Citizen page starts each journey independently. |
| 2 | Link department IDs + SSO | Linking works via `/api/identity/links`. **Keycloak full brokering stays stubbed** (`X-Auth-Jti`). |
| 3 | Scholarship fan-out | `ScholarshipJourneyIT` + citizen Start scholarship |
| 4 | Revenue killed mid-flight | Ops: Kill Revenue → start scholarship → status `PARTIALLY_VERIFIED`, Exceptions queue → Revive → Retry pending |
| 5 | Consent revoke | Citizen revoke (`POST /api/consent/{id}/revoke`) → next `AccessAuthority.authorize` is `DENIED` (`GRANT_DENIED` on Ops denials) |
| 6 | Audit verifier | `/audit.html`: Verify range (green) → Tamper head → fail (“entry was modified”). Tamper uses the **migrate** role; `samanvay_app` still cannot `UPDATE` |
| 7 | Onboard from spec | `/onboard.html`: Suggest mappings (advisory) → check boxes → Save approved only |
| 8 | Journey 3 is configuration | `FarmerSubsidyJourneyIT` + `git log` / Phase 3 tag: farmer subsidy landed as catalog/BPMN/policy, not Java |

## Known gaps

- No React SPA (HLD §11). Thin static HTML against existing APIs.
- Keycloak identity brokering is stubbed, not a live IdP hop.
- Flowable Boot 4 remains optional behind `WorkflowEngine`.
- Semantic/model mapping pass is omitted; suggestions are lexical only (HLD §8.2).
- Connector-health p50/p95 Micrometer charts are not built; SLA is due-at vs now on the ops table.
- `/internal/audit/ping` stays gone.

## Tag

Suggested git tag: `phase-4`.
