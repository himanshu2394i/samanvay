# Handoff: journeys-from-manifest (WIP — does NOT compile yet)

**Date:** 2026-09-30. **Audience:** next agent / Himanshu. Continues the onboarding vision.
**Approved design/spec:** `docs/superpowers/specs/2026-09-30-journeys-from-manifest-design.md` (read it first).

## ⚠️ Current state — the working tree is BROKEN mid-edit
The changes below are **uncommitted** on branch `claude/live-probe-monitoring`, layered on top of the
committed live-probe work. The backend **will not compile** because `CatalogServices` now
`implements JourneyWrite` but the two interface methods are **not yet written**. To get back to green
you must EITHER finish §"Remaining" below, OR revert the WIP files (list in §"Files touched").

Everything already committed + **deployed** (through PR #75 = discovery + live probe + monitoring, and
the CI fix) is fine and live — see `docs/handoff/2026-09-30-onboarding-realness-and-todos.md` for the
live URLs, credentials, deploy mechanics (SSH pem path, `git fetch origin <branch>` +
`git checkout -B <branch> FETCH_HEAD` + `./mvnw -Pfrontend -DskipTests package` +
`sudo systemctl restart samanvay`, and rebuild the simulator with
`docker compose build department-service && docker compose up -d --force-recreate department-service`).

## The feature (one line)
Onboarding a department from its manifest also picks up the **journeys** it publishes: they land as
DRAFT, show **Ready to publish** (all required connectors published) or **Pending — needs X (Dept)**,
and an **admin publishes** a ready one to make it live to citizens. Readiness is *computed* from real
connector availability, never a flag.

## Files touched so far (WIP, uncommitted)
COMPLETE + correct:
- `src/main/java/com/samanvay/catalog/internal/domain/JourneyEntity.java` — added all setters (was getters-only).
- `src/main/java/com/samanvay/catalog/api/JourneyDraft.java` — NEW record (code, name, referencePrefix, slaHours, consentPurpose, requester, requiredCategories List<String>, sources Map<String,String>).
- `src/main/java/com/samanvay/catalog/api/JourneyWrite.java` — NEW interface (`createJourney`, `publishJourney`).
- `docs/superpowers/specs/2026-09-30-journeys-from-manifest-design.md` — the approved spec.

PARTIAL / BROKEN:
- `src/main/java/com/samanvay/catalog/internal/service/CatalogServices.java` — added imports (JourneyDefinition/Draft/Policy/Write, ArrayList/LinkedHashMap/Map) and `implements … JourneyWrite`, BUT the field, constructor assignment, and both methods are MISSING → does not compile.

## Remaining to implement (in order)

### A. `CatalogServices` — finish the write path
1. Add field `private final JourneyRepository journeys;` (it's already a constructor PARAM — param 5 in
   both constructors — but currently NOT assigned to any field). Assign `this.journeys = journeys;` in
   the 9-arg constructor (the one that also sets `hosts`).
2. Policy JSON keys are **snake_case** (see `JourneyCatalogService.toJourney`): `accept_stale`,
   `sla_hours`, `requester`, `purpose`, `reference_prefix`, `sources` (object category→department).
3. Implement:
```java
@Override @Transactional
public JourneyDefinition createJourney(JourneyDraft d) {
    String code = InvalidRequestException.requireText(d.code(), "code");
    InvalidRequestException.requireText(d.name(), "name");
    if (d.requiredCategories() == null || d.requiredCategories().isEmpty())
        throw new InvalidRequestException("requiredCategories is required");
    if (journeys.existsById(code))
        throw new InvalidRequestException("A journey with code " + code + " already exists");
    JourneyEntity e = new JourneyEntity();
    e.setCode(code); e.setName(d.name().trim());
    e.setBpmnRef(code.toLowerCase());            // orchestration resolves generically by categories
    e.setRequiredCategories(d.requiredCategories().toArray(new String[0]));
    Map<String,Object> p = new LinkedHashMap<>();
    p.put("accept_stale", false); p.put("sla_hours", d.slaHours());
    p.put("requester", d.requester()); p.put("purpose", d.consentPurpose());
    p.put("reference_prefix", d.referencePrefix());
    p.put("sources", d.sources() == null ? Map.of() : d.sources());
    e.setPolicy(JSON.writeValueAsString(p)); e.setStatus("DRAFT");
    journeys.save(e);
    return new JourneyDefinition(code, d.name(), e.getBpmnRef(), d.requiredCategories(),
        new JourneyPolicy(false, d.slaHours(), d.requester(), d.consentPurpose(),
            d.referencePrefix(), Map.copyOf(d.sources() == null ? Map.of() : d.sources())),
        "DRAFT", null);
}

@Override @Transactional
public JourneyDefinition publishJourney(String code) {
    JourneyEntity e = journeys.findById(InvalidRequestException.requireText(code, "code"))
        .orElseThrow(() -> new InvalidRequestException("No journey " + code));
    List<String> missing = missingCoverage(e);
    if (!missing.isEmpty())
        throw new InvalidRequestException("Cannot publish " + code
            + " yet: no published connector for " + String.join(", ", missing));
    e.setStatus("PUBLISHED"); journeys.save(e);
    return toDefinition(e);   // parse policy like JourneyCatalogService.toJourney (copy that logic)
}

private List<String> missingCoverage(JourneyEntity e) {
    JsonNode policy = e.getPolicy()==null ? JSON.createObjectNode() : JSON.readTree(e.getPolicy());
    JsonNode src = policy.get("sources");
    List<String> missing = new ArrayList<>();
    String[] cats = e.getRequiredCategories()==null ? new String[0] : e.getRequiredCategories();
    for (String cat : cats) {
        String dept = (src!=null && src.get(cat)!=null) ? src.get(cat).asString() : null;
        boolean covered = connectors.findAll().stream().anyMatch(c ->
            "PUBLISHED".equals(c.getStatus()) && cat.equals(c.getDataCategory()) && dept!=null
            && dataSources.findById(c.getDataSourceCode())
                 .map(ds -> dept.equals(ds.getDepartmentCode())).orElse(false));
        if (!covered) missing.add(cat + (dept!=null ? " (" + dept + ")" : ""));
    }
    return missing;
}
// toDefinition(JourneyEntity): copy JourneyCatalogService.toJourney body (parses policy → JourneyPolicy).
```
Getters to rely on: `ConnectorEntity.getStatus()/getDataCategory()/getDataSourceCode()`,
`DataSourceEntity.getDepartmentCode()` — all confirmed to exist.

### B. `CatalogController`
Inject `CatalogDiscovery`-style: add `private final JourneyWrite journeyWrite;` (constructor param;
Spring injects the same `CatalogServices` bean). Add:
```java
@PostMapping("/journeys")
JourneyDefinition createJourney(@RequestBody JourneyDraft d) {
    requireText(d.code(), "code"); requireText(d.name(), "name");
    return journeyWrite.createJourney(d);
}
@PostMapping("/journeys/{code}/publish")
JourneyDefinition publishJourney(@PathVariable String code) { return journeyWrite.publishJourney(code); }
```
(`POST /api/catalog/**` is already ADMIN — no SecurityConfig change.)

### C. Access matrix
`src/test/java/com/samanvay/security/ApiAccessMatrix.java` — add:
```java
allow("POST /api/catalog/journeys", ADMIN);
allow("POST /api/catalog/journeys/{code}/publish", ADMIN);
```
(The matrix IT is exhaustive — missing routes fail it. Note it needs Postgres to run; it may be skipped
in CI without a DB, but add these anyway.)

### D. Enrich the manifest (BOTH sides — this is a breaking manifest-shape change)
`journeys[].requiredCategories` changes from `["INCOME_CERTIFICATE", ...]` (strings) to
`[{"category":"INCOME_CERTIFICATE","department":"REVENUE"}, ...]`, and each journey gains
`referencePrefix`, `slaHours`, `consentPurpose`, `requester`.
- `simulators/.../department/SamanvayManifestController.java` — change the nested `Journey`/add a
  `RequiredCategory(category, department)` record; update the SANDBOX_SUBSIDY sample:
  referencePrefix "SBX", slaHours 96, consentPurpose "SANDBOX_ELIGIBILITY", requester "SANDBOX",
  requiredCategories [{INCOME_CERTIFICATE, SANDBOX},{BANK_ACCOUNT, SANDBOX}].
- `src/main/java/com/samanvay/catalog/api/DepartmentManifest.java` — mirror the new `Journey` shape +
  a `RequiredCategory` record (the middleware parses it).
- `simulators/.../SamanvayManifestTest.java` — the `requiredCategories.toString()` contains
  "BANK_ACCOUNT" assertion should still hold (objects contain `"category":"BANK_ACCOUNT"`), but re-run it.
- Because both the manifest AND the parser change, **deploy the simulator and the app together**.

### E. Frontend
- `frontend/src/api/staffTypes.ts` — `ManifestJourney`: `requiredCategories: {category:string;department:string}[]`
  plus `referencePrefix`, `slaHours`, `consentPurpose`, `requester`. Add a `JourneyDraft` type matching the backend.
- `frontend/src/api/staffApi.ts` — `createJourney(draft)` → `POST /api/catalog/journeys`;
  `publishJourney(code)` → `POST /api/catalog/journeys/${enc(code)}/publish`.
- `frontend/src/surfaces/staff/pages/OnboardingPage.tsx` `onboardFromManifest` — after connectors, for
  each manifest journey call `createJourney({ code, name, referencePrefix, slaHours, consentPurpose,
  requester, requiredCategories: j.requiredCategories.map(r=>r.category), sources:
  Object.fromEntries(j.requiredCategories.map(r=>[r.category, r.department])) })`. Update the
  DiscoverPanel journey rendering to the new shape (show each category with its department, SLA, purpose).
- `frontend/src/surfaces/staff/pages/CatalogPage.tsx` — the Journeys table gets a **Readiness** column
  and a **Publish** button. Load data sources too (add `api.listDataSources()` to the page's
  `Promise.all`, or reuse the DataSourcesPanel fetch). Readiness per journey: for each requiredCategory
  `cat`, `dept = journey.policy.sources[cat]`; covered = a connector in `connectors` with
  `category.code===cat` AND `status==='PUBLISHED'` AND its `dataSourceCode`'s data source has
  `departmentCode===dept`. If all covered → "Ready to publish" + enabled **Publish** button (DRAFT only);
  else → "Pending — needs " + the missing `cat (dept)` list. Publish → `api.publishJourney(code)` → reload.
  NOTE: `GET /api/catalog/connectors` returns only PUBLISHED connectors, which is exactly what readiness needs.

### F. Tests
- Backend: a `JourneyWriteTest` (mock repos) — create requires code+≥1 category, rejects duplicate;
  publish returns 400 with the missing list when a required connector is absent, 200 when all covered.
  Constructing `CatalogServices` now needs a `JourneyRepository` mock (previously passed null).
- Frontend: extend `admin.flow.test.tsx` — onboarding creates journeys (mock `POST /journeys`); catalog
  shows readiness and gates/enables Publish (mock `/data-sources`, `/connectors`, `POST /journeys/{code}/publish`).
  REMEMBER: the catalog view test asserts `m.unhandled === []`, so mock every new call the page makes.
- Run frontend suite with `npx vitest run --pool=forks` (the thread pool crashes on this machine otherwise).

### G. Ship
Commit to a branch → PR → deploy the branch to the demo (fetch + `checkout -B <b> FETCH_HEAD` +
`-Pfrontend package` + restart + **rebuild the simulator** for the manifest change) → verify:
`GET dbt…/.well-known/samanvay/manifest` shows the enriched journeys; `POST /api/catalog/journeys`
returns 401 unauth; admin Onboarding→Discover→Register creates the journeys; Catalog shows readiness.

## Gotchas
- Admin flows can't be manually tested by the assistant (TOTP). Verify endpoints via curl (401 unauth =
  deployed+secured) and have Himanshu click through as `dev-admin`.
- Merges to main are blocked for the assistant — deploy the branch directly (branch preview), and
  Himanshu merges #75 (and this) via review when ready. The demo currently runs the branch.
- `bpmnRef = code.toLowerCase()` is a guess; the journeys-real orchestration resolves generically by
  required categories (see `JourneysRealResolutionTest`), so a generic bpmn_ref should be fine, but a
  runtime fetch still needs the journey's connectors PUBLISHED (which is exactly what "Ready" gates on).
