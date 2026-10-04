# Samanvay

**Government Digital Platform Interoperability & Federated Service Delivery Platform**

SIH26129 — *System integration and interoperability among government digital platforms,
resulting in fragmented service delivery* · Government of Maharashtra

---

> **The platform does not centralize government data. It makes distributed government data
> discoverable, accessible and interoperable under explicit authorization.**

Samanvay is an interoperability platform, not a service portal. Government departments keep
ownership of their data; Samanvay owns only the *interoperability state* — identity links,
consent, discovery metadata, workflow, tracking, audit and mappings.

Citizen journeys (post-matric scholarship, farmer subsidy, income certificate renewal, DBT bank account seeding) run on **each department's own
portal**, not on Samanvay: Samanvay has no citizen sign in and no citizen screens. The department's portal calls Samanvay behind the scenes, with
the citizen's consent (signed by the department). The journeys exist as **evidence that the platform is generic**, not as the product.

## Live demo & one-click access (for judges)

**Live:** <https://app.3.109.201.126.nip.io/app/> — demo data only; nothing here is a real record.

There is **no demo login**: everyone signs in through Keycloak.

- **Citizens** do not use Samanvay. They sign in on a department's own portal (`https://<department>/portal/`) with that department's
  mobile number, password and one-time code; when a service needs another department's records they log in at that department's own login.
- **Staff** use the ready-made accounts `dev-officer`, `dev-reviewer` and `dev-admin` (temporary password `<user>-change-me`):
  the first sign-in asks for a new password and for an authenticator code (TOTP) to be enrolled, as for any real staff
  account. A passkey also works.

| Open this | Sign in as | What to look at |
|---|---|---|
| [`/app/#/staff/admin/onboarding`](https://app.3.109.201.126.nip.io/app/#/staff/admin/onboarding) | `dev-admin` | Onboard a department from just its URL; it picks up the department's documents **and its services (journeys)**; "Run trial fetch" |
| [`/app/#/staff/admin/schemas`](https://app.3.109.201.126.nip.io/app/#/staff/admin/schemas) | `dev-admin` | The **central schema** the departments' fields are matched onto; add a schema or a new version |
| [`/app/#/staff/admin/departments`](https://app.3.109.201.126.nip.io/app/#/staff/admin/departments) and [`/app/#/staff/admin/journeys`](https://app.3.109.201.126.nip.io/app/#/staff/admin/journeys) | `dev-admin` | The onboarded **Departments** and **Journeys**; each journey's **readiness** is computed from real connector availability; publish a ready one |
| [`/app/#/staff/officer/exceptions`](https://app.3.109.201.126.nip.io/app/#/staff/officer/exceptions) | `dev-officer` | Exception queue, retries, bank-account review, live ops metrics |
| [`/app/#/staff/reviewer/queue`](https://app.3.109.201.126.nip.io/app/#/staff/reviewer/queue) | `dev-reviewer` | The identity-matching review queue (machines propose, humans dispose) |

The `#` in the staff URLs matters — the app uses hash routing. The **citizen** experience is each department's own portal
(`/portal/` on the Revenue, DBT, Education and Agriculture servers; see `deploy/README.md`). Staff open **Journeys, then a journey's Status**
(`/app/#/staff/admin/journeys/<code>`) to see whether it is connected and working, with its middle-layer log.

## Design principles

| | |
|---|---|
| **P1** | Federated by default, indexed centrally |
| **P2** |The control plane decides, the data plane moves|
| **P3** | Machines propose, humans dispose, the decision is audited |
| **P4** | The canonical model is a transport contract, not ownership |
| **P5** | Concrete, then generalize, then prove |
| **P6** | Depend on capabilities only where a swap is real |

## Documentation

| Document | Contents |
|---|---| 
| [docs/architecture/HLD.md](docs/architecture/HLD.md) | **High Level Design — combined.** Single source of truth for principles, flows and technology decisions |
| [docs/architecture/hld/](docs/architecture/hld/README.md) | High Level Design — one charter per module, plus the dependency graph |
| [docs/architecture/LLD.md](docs/architecture/LLD.md) | **Low Level Design — combined.** Package layout, DB conventions, error handling, testing, cross-module sequence diagrams |
| [docs/architecture/lld/](docs/architecture/lld/README.md) | Low Level Design — one document per module: DDL, class design, sequences, tests |
| [docs/FINAL-CHANGES.md](docs/FINAL-CHANGES.md) | The department-services redesign: decisions, build log and what is still open |
| [docs/contracts/login-assertion.md](docs/contracts/login-assertion.md) | The signed assertion a department login returns (how a citizen proves who they are there) |

### Modules

| # | Module | Plane | Phase | HLD | LLD |
|---|---|---|---|---|---|
| 01 | `audit` | Cross-cutting | 0 | [charter](docs/architecture/hld/01-audit.md) | [LLD](docs/architecture/lld/01-audit.md) |
| 02 | `catalog` | Control | 1 | [charter](docs/architecture/hld/02-catalog.md) | [LLD](docs/architecture/lld/02-catalog.md) |
| 03 | `identity` | Control | 1 → 2 | [charter](docs/architecture/hld/03-identity.md) | [LLD](docs/architecture/lld/03-identity.md) |
| 04 | `registry` | Control | 1 → 2 | [charter](docs/architecture/hld/04-registry.md) | [LLD](docs/architecture/lld/04-registry.md) |
| 05 | `consent` + `AccessAuthority` | Control | 1 | [charter](docs/architecture/hld/05-consent.md) | [LLD](docs/architecture/lld/05-consent.md) |
| 06 | `connector` | Data | 1 → 2 | [charter](docs/architecture/hld/06-connector.md) | [LLD](docs/architecture/lld/06-connector.md) |
| 07 | `orchestration` | Data | 1 → 2 | [charter](docs/architecture/hld/07-orchestration.md) | [LLD](docs/architecture/lld/07-orchestration.md) |
| 08 | `tracking` | Data | 1 | [charter](docs/architecture/hld/08-tracking.md) | [LLD](docs/architecture/lld/08-tracking.md) |
| 09 | `notifications` | Data | 2 | [charter](docs/architecture/hld/09-notifications.md) | [LLD](docs/architecture/lld/09-notifications.md) |

## Stack

**Backend** Java 21 · Spring Boot 4 (Spring Framework 7) · Spring Modulith 2.x ·
PostgreSQL 16 · Flowable 8.x (behind a port) · Keycloak · Resilience4j · Flyway

**Frontend** One React + TypeScript + Vite project (`frontend/`) with two builds: the staff console (officer desk, reviewer queue, admin
onboarding, catalog and journey status), served by Spring at `/app/`; and the **department citizen portal**, built by `scripts/build-portal.sh`
into each department service's `static/portal/`. A few thin static pages remain for operations (`/demo.html` and friends).

**Runtime** Docker Compose — fully offline, deterministic seed data

## Getting started

Requires: Java 21+ (JDK 25 works fine — Maven compiles down to release 21), Docker Desktop. No Maven install needed, the wrapper handles it.

```bash
./mvnw -DskipTests package   # also builds the Keycloak email-code provider (target/keycloak-providers)
docker compose up -d          # Postgres + dev Keycloak (http://localhost:8180, admin/admin) + Mailpit (http://localhost:8025)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

Use the `dev` or `demo` profile locally: only those two point at the compose Keycloak
(`http://localhost:8180`, see `application-dev.yml`). Any other boot refuses to start unless
`SAMANVAY_STAFF_ISSUER_URI` and `SAMANVAY_CITIZEN_ISSUER_URI` are set to https issuers.

Every `/api/**` call needs a Keycloak bearer token (the actor is taken only from the token).
Pages have a sign-in bar (Authorization Code + PKCE; the issuer and client come from
`GET /ui/auth-config`). There is no demo or paste-token sign-in in any profile. Dev realms are imported from
`keycloak/realms/` (regenerate with `python3 keycloak/gen_realms.py`; set `SAMANVAY_EXTRA_ORIGINS` to add a deployed
https origin; the default is the AWS demo's, so regenerating reproduces the committed files):

| Realm | Dev users | Sign-in |
|---|---|---|
| `samanvay-staff` | `dev-officer` (department `SCHOLARSHIP`), `dev-reviewer`, `dev-admin`; temporary password `<user>-change-me` | passkey (user verification required) **or** password + TOTP; no password-only path |
| `samanvay-citizen` | `dev-citizen` / `dev-citizen-change-me`, or sign up | email + password (sign-up asks for first name, last name, email, password; email is the user name) **or** passkey; no TOTP; direct grants denied |

Department service accounts (client credentials, secret generated by Keycloak, read it in the admin console) get
`ROLE_DEPARTMENT` plus one `source:<code>` scope per data source: `dept-revenue`, `dept-dbt`, `dept-education`,
`dept-agriculture` (the clients the onboarding plan's "Issue caller credential" step names) and the older
`dept-scholarship-dev`.
Issuers are configurable with `SAMANVAY_STAFF_ISSUER_URI` / `SAMANVAY_CITIZEN_ISSUER_URI`.

Staff entry: `http://localhost:8080/` is a short landing page for the Samanvay team and officers, linking to the staff console at `/app/`.

| URL | What it is |
|---|---|
| `/` | Staff landing page |
| `/app/` | Staff console (officer, reviewer, admin) |
| `/demo.html` | Optional Samanvay operations cutaway |
| `http://localhost:8091..8094/portal/` | Revenue, DBT, Education, Agriculture citizen portals (the department services, run locally) |

Spoken eight-minute walk: [docs/demo/JUDGE_SCRIPT.md](docs/demo/JUDGE_SCRIPT.md). One-pager: [docs/demo/ONE_PAGER.md](docs/demo/ONE_PAGER.md). Technical rehearsal: [docs/demo/PHASE4_RUNBOOK.md](docs/demo/PHASE4_RUNBOOK.md).

Samanvay remains the interoperability middle layer. The department portals are its callers, not the product. (The older judge script under
`docs/demo/` describes the previous Samanvay-hosted portals and is out of date.)

## Department services: four separate stand-in departments (dev/demo only)

`departments/` holds **four separate department services** (Revenue, DBT, Education, Agriculture), each its own Spring Boot
app with its own fake data, its own protocols and security, its own published manifest, and its own citizen login. Samanvay
learns each one from its manifest and onboards it in one go; it never holds their documents.

| Dept | Port | Documents | Protocols | Security Samanvay satisfies | Citizen login |
|---|---|---|---|---|---|
| `revenue/` | 8091 | income / caste / domicile certificates (own keys, so a `resolve` step), 7/12 land parcel | REST + SFTP | API key (+ optional IP allow-list); SFTP password | user ID + password |
| `dbt/` | 8092 | bank account | REST | OAuth2 client credentials | mobile + one-time code |
| `education/` | 8093 | marks | SOAP | WS-Security UsernameToken | seat number + date of birth |
| `agriculture/` | 8094 | farmer record (DB view), crop record | JDBC + SFTP | read-only DB account; SFTP password | user ID + password |

How it fits together (details and decisions: [docs/FINAL-CHANGES.md](docs/FINAL-CHANGES.md)):

1. **Manifest.** Each department publishes `GET /.well-known/samanvay/manifest` (v2): documents, how to reach each over its
   protocol, the security parameters it needs (names only, never values), whether a `resolve` step is needed, its journeys,
   and an `identity` block for its login.
2. **One-go onboarding.** `POST /api/catalog/onboard/plan` reads the manifest and returns a plan (nothing changes);
   `POST /api/catalog/onboard` creates the department, data sources, connectors, mappings and journeys as drafts, in one
   transaction, for exactly the documents an admin ticked (refused if the manifest changed since). Field matches are proposals an
   admin approves. The staff console has the screen: **Onboard a department in one go**. Secrets and SFTP/JDBC hosts stay with the
   operator: the plan lists each step (provision a secret, pin a host key, issue the department portal a caller credential).
3. **Central schema stays seeded** (V203): a department's document types are seeded first, then its fields are mapped onto them.
4. **Linking is per department, by that department's own login.** The citizen logs in at the department; it returns a signed
   assertion ([contract](docs/contracts/login-assertion.md)); Samanvay verifies it and saves the link. Later journeys need
   only consent.
5. **Fetching** sends the person ID (or the document key from the optional `resolve` step) plus Samanvay's credentials over the
   declared protocol.

Run one: `./mvnw -f departments/pom.xml -pl revenue -am spring-boot:run -Dspring-boot.run.profiles=dev` (the `dev` profile turns on demo mode; without it a department refuses to start on the dev default secrets). Run all with their data stores:
`docker compose up -d dept-revenue dept-dbt dept-education dept-agriculture revenue-sftp agriculture-db agriculture-sftp`.
More: [departments/README.md](departments/README.md).

## Department simulators (dev/CI only)

> **The shared sandbox department is gone.** `simulators/` used to also serve a fake income / marks / bank "department" on one
> service. The four separate [department services](#department-services-four-separate-stand-in-departments-devdemo-only) in
> `departments/` replace it; see [docs/runbooks/department-cutover.md](docs/runbooks/department-cutover.md). `simulators/` now holds
> only the bank-check simulator below, which core's bank verification still uses.

`simulators/` is a **separate** Spring Boot app (own `pom.xml`, package `in.samanvay.simulators`,
port 8090). It stands in for external department APIs. The first one implements our own
[bank-check contract v1](docs/contracts/bank-check-v1.yaml): a public IFSC lookup plus a one-call account
check that returns only `accountStatus` and `nameMatch`, never the holder's name. Its fixtures are
described in [`SPEC-NOTES.md`](simulators/src/main/resources/fixtures/SPEC-NOTES.md).

```bash
./mvnw -f simulators/pom.xml spring-boot:run        # http://localhost:8090
curl localhost:8090/SBIN0000300                     # IFSC lookup (public, open RBI data)
curl -u samanvay-sim-key:sim-secret-change-me -H 'Content-Type: application/json' \
  -d '{"ifsc":"SBIN0000300","accountNumber":"00001000000001","applicantName":"Asha Patil"}' \
  localhost:8090/v1/bank-checks
```

The simulator is **never a live source in production**. samanvay-core has no build dependency on it and
reaches it only over HTTP, through the same client it will use for the live API. Every simulator
response carries `X-Samanvay-Simulator: true`. Which endpoint a source uses will be selected by
`samanvay.sources.<code>.mode=sandbox|simulator|live`, which comes in a separate PR.

### Property (SFTP) and pollution (JDBC) fixtures for the licence journey

REST and SOAP are served by the [department services](#department-services-four-separate-stand-in-departments-devdemo-only). Two more
compose services keep the licence journey's **SFTP** (property) and **JDBC** (pollution) sources on real transports, each with FAKE
sandbox data (never a real system):

- **`department-db`** (Postgres, `:5433`) — the **JDBC** source `sandbox-pollution-jdbc` (catalog V198). Seeded
  by `docker/department-db/init.sql` with a `pcb_clearance` table and a read-only role `pcb_ro`. A fetch runs a
  parameterized `SELECT` through the real `JdbcQueryClient` (`JdbcSqlGuard` permits SELECT only).
- **`department-sftp`** (`atmoz/sftp`, `:2222`) — the **SFTP** source `sandbox-property-sftp` (catalog V193).
  Serves `docker/department-sftp/property.csv` over real SFTP through `SftpCsvClient`.

Credentials are read from `SecretStore`, never from config. In the dev/demo profile (`EnvSecretStore`) that means
one env var per source, base64 of `username:password`:

```bash
docker compose up -d department-db department-sftp        # plus the base services

# JDBC read-only credential  (pcb_ro:pcb_ro_demo)
export SAMANVAY_SECRET_SOURCE_SANDBOX_POLLUTION_JDBC_CREDENTIAL="$(printf 'pcb_ro:pcb_ro_demo' | base64)"
# SFTP credential            (fixtureuser:fixturepass)
export SAMANVAY_SECRET_SOURCE_SANDBOX_PROPERTY_SFTP_CREDENTIAL="$(printf 'fixtureuser:fixturepass' | base64)"

# SFTP host key is pinned (no trust-on-first-use). Capture the running server's fingerprint once.
# Use the RSA key: the SftpCsvClient (Apache MINA sshd) negotiates rsa-sha2 with atmoz/sftp, so the
# pin must be that key's fingerprint (an ed25519 pin would fail with "Server key did not validate").
export SAMANVAY_SANDBOX_SFTP_HOSTKEY="$(ssh-keyscan -t rsa -p 2222 localhost 2>/dev/null \
  | ssh-keygen -lf - | awk '{print $2}')"

./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

A fetch through `ConnectorRuntimeImpl` for `sandbox-pollution@1` / `sandbox-property@1` is then a real JDBC query /
SFTP download. Locally verifiable without Docker: `JdbcRealTransportTest` (H2) and `SftpCsvRealTransportTest`
(in-process sshd) prove the same code paths, and the `*BootTest`s prove a LIVE source refuses to start without its
credential.

## Contributing

1. Branch off `main`, work in your module's package (`com.samanvay.<module>.*`).
2. Open a PR against `main`. CI must pass; the technical lead's review is required (enforced via [CODEOWNERS](CODEOWNERS) and branch protection — direct pushes to `main` are blocked).
3. Read your module's charter in [`docs/architecture/hld/`](docs/architecture/hld/README.md) before writing code — it defines what your module owns, its public interface, and its acceptance criteria.
