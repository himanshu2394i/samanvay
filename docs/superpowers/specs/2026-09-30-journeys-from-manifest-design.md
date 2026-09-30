# Journeys from the manifest — design

**Date:** 2026-09-30. **Status:** approved, building.

## Goal
When a department is onboarded from its published manifest, also pick up the **journeys** (services)
it offers — not just its documents. Journeys are *discovered and onboarded*, never hand-authored in
the middleware. A journey's readiness ("is it up for service?") reflects whether its pieces actually
exist, and a human admin publishes it.

## The two states (the user's framing)
- A journey whose required documents are **not all wired** (no published connector for some category)
  → **Pending** ("needs INCOME (Revenue), MARKS (Education)"). This is the "still to be implemented
  here" bucket.
- A journey whose required documents are **all wired** (published connectors) → **Ready**, and an admin
  publishes it → **up for service**.

Readiness is **computed from real connector availability**, not a declared flag.

## 1. Enriched manifest (department side)
Each `journeys[]` entry carries the full spec:
```json
{ "code": "POST_MATRIC_SCHOLARSHIP", "name": "...", "description": "...",
  "referencePrefix": "SCH", "slaHours": 72, "consentPurpose": "SCHOLARSHIP_ELIGIBILITY",
  "requester": "SCHOLARSHIP",
  "requiredCategories": [ { "category": "INCOME_CERTIFICATE", "department": "REVENUE" },
                          { "category": "MARKS", "department": "EDUCATION" } ] }
```
Changed: `simulators/.../SamanvayManifestController` (publish it), `DepartmentManifest.Journey`
(parse it), frontend `ManifestJourney` type. `requiredCategories` goes from `List<String>` to a list
of `{category, department}`.

## 2. Backend — two new ADMIN endpoints
- `POST /api/catalog/journeys` — create as **DRAFT**. Body `JourneyDraft`: code, name, referencePrefix,
  slaHours, consentPurpose, requester, and `sources` (category→department) + `requiredCategories`
  (the category list). Builds `catalog_journey` (bpmn_ref defaults to a generic flow, policy JSON =
  {slaHours, requester, purpose, referencePrefix, sources, acceptStale:false}). Validates: code unique,
  ≥1 category. New `JourneyDraft` record + `JourneyWrite` interface implemented by `CatalogServices`.
- `POST /api/catalog/journeys/{code}/publish` — DRAFT→PUBLISHED, **only if ready** (every required
  category has a PUBLISHED connector whose data source belongs to the named department), else 409.
- Both added to `ApiAccessMatrix` + `SecurityConfig` (POST /catalog/** already ADMIN; publish path
  matches; no GET added).

## 3. Readiness (no new endpoint)
Computed client-side for display + button-gating, and re-checked server-side on publish. A published
connector "covers" (category, department) when its `category` matches and its data source's
`departmentCode` matches. A journey is Ready iff every `(requiredCategory, policy.sources[category])`
pair is covered. Missing pairs are listed as "needs …".

## 4. Onboard-from-manifest wiring (frontend)
`onboardFromManifest` gains a final step: for each manifest journey, `POST /journeys` with the mapped
draft. One Register action → department + draft connectors + draft journeys.

## 5. UI
- **DiscoverPanel:** render enriched journeys (required categories with their departments, SLA, purpose).
- **CatalogPage → Journeys:** a **Readiness** column (Ready to publish / Pending — needs X) and, for a
  ready DRAFT journey, a **Publish** button (admin) → live to citizens.

## Testing
- Backend: journey create validation (code required, ≥1 category), publish readiness gating (409 when a
  required connector is missing; 200 when all covered) — unit tests with mock repos.
- Frontend: onboarding creates journeys; catalog shows readiness + gates/enables Publish; build+lint.

## Out of scope
- From-scratch journey authoring wizard (journeys come from manifests).
- Auto-publish (admin publishes explicitly).
- Fallback-when-department-down (department-portal-side; dropped).
