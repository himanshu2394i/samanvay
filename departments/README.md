# Department stand-ins

Four separate department services, each its own Spring Boot app with its own data, manifest, security scheme and
citizen login. They are **fake data only** and never live systems. Samanvay reaches them only over the network; a
test in each (`DepartmentBoundaryTest`) fails if one ever depends on Samanvay core code.

| Dir | Port | Documents | Protocols | Security Samanvay must satisfy | Citizen login |
|---|---|---|---|---|---|
| `revenue/` | 8091 | income, caste, domicile certificates (own keys, so a `resolve` step); 7/12 land record | REST + SFTP (`revenue-sftp` :2223) | `X-Api-Key` header + optional IP allow-list; SFTP password | user ID + password |
| `dbt/` | 8092 | bank account | REST | OAuth2 client credentials (`/oauth/token`, bearer) | mobile + one-time code |
| `education/` | 8093 | marks statement | SOAP 1.1 | WS-Security UsernameToken in the SOAP header | seat number + date of birth |
| `agriculture/` | 8094 | farmer record; crop sowing report | JDBC read-only view (`agriculture-db` :5434) + SFTP (`agriculture-sftp` :2224) | DB account `agri_ro` (view only); SFTP password | user ID + password |

Every department publishes `GET /.well-known/samanvay/manifest` (v2): documents with fields, protocol access details,
required security parameters (never values), whether a `resolve` step is needed, the journeys it offers, and an
`identity` block (login URL, public keys, ID type). Login contract: [docs/contracts/login-assertion.md](../docs/contracts/login-assertion.md).

Run one: `./mvnw -f departments/revenue/pom.xml spring-boot:run`. Test one: `./mvnw -f departments/revenue/pom.xml test`.
Run all with their data stores: `docker compose up -d dept-revenue dept-dbt dept-education dept-agriculture revenue-sftp agriculture-db agriculture-sftp`.

Dev credentials are env-overridable defaults in each `application.yml`; they exist only so the stand-ins work out of the box.

## Onboarding them, and the end-to-end test

Samanvay onboards each from its manifest in one go (staff console: *Onboard a department in one go*, or
`POST /api/catalog/onboard/plan` then `/onboard`); see [docs/runbooks/department-cutover.md](../docs/runbooks/department-cutover.md).
Dev credentials for the stand-ins: `eval "$(scripts/dev-department-secrets.sh)"`.

Each department **signs its manifest** (`X-Samanvay-Signature`, ES256; key kept in `<dept>.manifest.key-file`, default
`manifest-signing-key.jwk`, git-ignored) and can require a **discovery credential** (`<dept>.manifest.discovery-key`, header
`X-Discovery-Key`; empty = public). See [docs/contracts/manifest-signature.md](../docs/contracts/manifest-signature.md).

`DepartmentsEndToEndIT` (root project) runs the four jars as real processes, two SFTP servers and Agriculture's own Postgres
(seeded by `agriculture/db/init.sql`), onboards all four from their real manifests, links a citizen by logging in at each
department, and fetches a document from each through the real connector runtime. Build the jars first with
`scripts/build-departments.sh` (the test is skipped, not failed, when they are missing). The manifests it relies on are captured
as fixtures by `scripts/capture-department-manifests.sh`.

