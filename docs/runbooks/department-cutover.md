# Cutting a deployment over to the four department services

How to move from the old shared sandbox (the mock-backed `dept-*` / `sandbox-*` sources) to the four separate department services
in `departments/`. It is a **runbook, not a migration**: the old catalog rows stay as history, and the old sources keep serving
until the new connectors are published. The old `simulators/` department-service code has been deleted; in the demo profile
`DemoDepartmentBootstrap` onboards and publishes the four departments at startup (a connector is published only if its trial fetch passes),
which needs the operator secrets (`scripts/dev-department-secrets.sh`) and the SFTP host-key pins exported first. Background and decisions: [docs/FINAL-CHANGES.md](../FINAL-CHANGES.md).

## Why it is safe

- Onboarding creates **drafts**. Connector resolution picks the highest PUBLISHED version of a department's connector for a
  category, so a new version (v2) changes nothing until it is published, and publishing is the moment of cutover.
- An existing connector for the same department and category becomes a new version of the same connector; the old version is
  still there to fall back to (unpublish the new one / publish a corrected version).
- All writes in an onboarding happen in one transaction; a refusal leaves nothing behind.

## Steps

1. **Start the departments** (dev/demo): `docker compose up -d dept-revenue dept-dbt dept-education dept-agriculture revenue-sftp agriculture-db agriculture-sftp`.
   Check each: `curl localhost:8091/.well-known/samanvay/manifest` (8092 DBT, 8093 Education, 8094 Agriculture).
   In production each department is the real service at its own HTTPS address.
2. **Seed the central schema first** (a migration) if a department publishes a document type that has no central schema.
   The four departments' types are already seeded (V203). The plan says "seed it first" for any that are not.
3. **Review the plan** in the staff console (Admin, Onboarding, *Onboard a department in one go*) or
   `POST /api/catalog/onboard/plan {"baseUrl": "..."}`. Read the documents, proposed field matches, journeys and the
   steps-for-an-operator list.
   The plan also shows the **manifest signing key fingerprint**. Confirm that fingerprint **with the department out of band** (phone,
   ticket, signed letter) before onboarding; Samanvay pins it, and later manifests must be signed by that key
   ([docs/contracts/manifest-signature.md](../contracts/manifest-signature.md)). An unsigned manifest is refused unless
   `samanvay.catalog.allow-unsigned-manifests=true` (local development only).
   If the department keeps its manifest private it issued you a **discovery credential**: provision it first under
   `manifest-<host[-port]>-credential` (the refusal message names the exact key), otherwise the plan cannot even read the manifest.
4. **Do the operator steps the plan lists, before onboarding or at least before publishing:**
   - `PROVISION_SECRET`: put the credential the department gave you into the SecretStore under the key shown, as a JSON object
     keyed by the parameter names shown (env var `SAMANVAY_SECRET_<KEY>` as base64, or a mounted file). In dev:
     `eval "$(scripts/dev-department-secrets.sh)"`.
   - `CONFIGURE_SFTP` / `CONFIGURE_JDBC`: set the source's host, remote path and **host-key pin** (SFTP) or JDBC URL (TLS) in
     configuration and restart. A manifest cannot do this for you, by design. Dev defaults are already in `application-dev.yml`.
   - `CONFIGURE_HTTPS`: REST and SOAP sources are called over HTTPS. (Dev/demo only: `samanvay.sources.department-service.urls.<code>` maps a source to a local URL.)
   - `ISSUE_CALLER_CREDENTIAL`: have the identity-provider admin create the department portal's client-credentials client in the
     staff realm (department claim = the department code, one `source:<code>` scope per data source), add its client ID to
     `samanvay.security.staff.allowed-clients`, and give the department its secret out of band.
5. **Onboard**: tick the documents, approve the proposed field matches, *Onboard*. Check the result: data sources, connector
   drafts, mapping refs, journey drafts, and any skipped items.
6. **Test and publish each connector** (the Onboarding page's *Run trial fetch*, then publish): the config test passes, then publish. Until the new
   version is published the old one serves. Probe each data source (reachability).
7. **Link a test citizen** through the department's own login (the portal's *Log in at ...* button), then run a journey end to end.
8. **Publish the journeys** once every required category has a published connector (the Journeys page shows readiness).
9. **Only then** retire what is left of the old sandbox: remove the old `dept-*` / `sandbox-*` source configuration (and the
   `department-db` / `department-sftp` compose services once nothing uses them). Their catalog rows are harmless and can stay.

## Rolling back

Unpublish the new connector version (or publish a corrected one); the previous version becomes the highest published version again.
No data is lost: Samanvay stores links, consents and audit, never the documents.

## Known limits

- The connector "test" is a configuration check and the data-source probe is reachability. A true per-document trial fetch needs a
  sample person ID the manifest does not publish yet.
- REST connectors support GET with path and query inputs; POST bodies, SOAPAction, HMAC request signing, client certificates and
  SFTP key login are not implemented.
- A department's login keys are fetched with an unauthenticated request. Their URL (and the login URL) must be on the same host as
  the base URL you onboarded from, or the plan is refused; a department that serves login from another host would need an
  operator allow-list (not built).
- The reference department services keep their manifest signing key in a file (compose: a named volume at `/data`). If the key
  file is lost the key rotates, and Samanvay (including the demo bootstrap, which never auto-approves a CHANGED key) refuses the
  manifest until an admin approves the new fingerprint in the staff console.
