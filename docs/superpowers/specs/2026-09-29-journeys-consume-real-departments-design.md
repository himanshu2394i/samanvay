# Journeys consume real independent departments (replace mock)

_Design spec — 2026-09-29_

## Goal

Prove the Samanvay thesis end to end: it is a **middle interoperability layer** that lets a
citizen journey pull data from **real, independent department services** over real protocols,
instead of collecting documents by hand. Today the three journeys run the Samanvay consent→fetch
flow, but every category resolves to the in-process **mock backend** (`mock.samanvay.test`). This
change **repoints the journey connectors at real independent services** so the journeys genuinely
consume them — replacing the mock, not toggling a mode.

Scope is **A-now, path-to-B**: make every journey consume at least one real service now, structured
so remaining categories become real later by adding endpoints (no architectural change).

## Background — current state

All three journeys already fan out over the correct protocols, but 100% mock:

| Journey | Categories (protocol) — all currently MOCK |
|---|---|
| `POST_MATRIC_SCHOLARSHIP` | INCOME_CERTIFICATE (REST), CASTE_CERTIFICATE (REST), MARKS (SOAP), BANK_ACCOUNT (REST) |
| `BUSINESS_NOC` | PROPERTY (SFTP_CSV), FIRE_NOC (REST), POLLUTION_CLEARANCE (JDBC), LAND_RECORD (REST) |
| `FARMER_SUBSIDY` | LAND_PARCEL (REST), CROP_RECORD (REST), BANK_ACCOUNT (REST) |

Routing rule (unchanged): a connector routes to the mock backend when its data source's
`base_host == mock.samanvay.test`; any other host routes through the real adapter
(`RestAdapter`/`SoapAdapter`/`SftpCsvAdapter`/`JdbcAdapter`), and the dev/demo config maps the
source code to the real service URL (`samanvay.sources.department-service.urls` / `.sftp` / `.jdbc`).
This is the same mechanism V193/V198 used to onboard the standalone sandbox sources.

Real services that already exist (built + deployed this project):
- `department-service` (`simulators` module) — REST `GET /v1/income`, SOAP `POST /marks/service`.
- `department-db` (Postgres) — `pcb_clearance` table, queried read-only as `pcb_ro`.
- `department-sftp` (`atmoz/sftp`) — `/outbound/property.csv`.

So **INCOME, MARKS, PROPERTY, POLLUTION are already served by real services.** The only new endpoint
needed is **BANK**, which is shared by Scholarship and Farmer — one endpoint gives both journeys a
real hop.

## Approach (chosen)

**A-now, path-to-B, replace.** Repoint the five journey connectors whose categories have (or gain) a
real service — **INCOME, MARKS, PROPERTY, POLLUTION, BANK** — to real-transport data sources. Leave
CASTE, FIRE_NOC, LAND_RECORD, LAND_PARCEL, CROP on mock for now; they upgrade later by adding
endpoints and repointing, with no design change.

Alternatives considered and rejected:
- **B now (fully real):** requires ~6 more department endpoints/datasets across departments — too
  large for one change; deferred.
- **Mode toggle (mock default, real opt-in):** rejected by the requirement to *replace* mock so the
  journeys are genuinely real, not a demo switch.

## Components to build

### 1. New `/bank` endpoint on `department-service` (simulators module)
Add a REST endpoint returning a DBT/bank-account record shaped to what the `BANK_ACCOUNT` journey
connector (`dbt-bank@1`) and its mapping expect. Marked with the simulator marker like the other
simulator responses. Covered by the simulators module's own tests.

### 2. Migration `V199` — repoint the five journey connectors
Surgical, additive-then-update (forward-only, follows V198):
- **INSERT** five real-transport data sources, one per category, `base_host` a non-mock `.example`
  host, correct protocol and auth:
  - `dept-income-rest` (REST), `dept-marks-soap` (SOAP), `dept-bank-rest` (REST) — `auth_type NONE`.
  - `dept-property-sftp` (SFTP_CSV, `PASSWORD`, `auth_config_ref secret:dept-property-sftp`).
  - `dept-pollution-jdbc` (JDBC, `PASSWORD`, `auth_config_ref secret:dept-pollution-jdbc`).
- **UPDATE** the five journey connectors to point at the new real sources and align their FETCH
  block (`endpoint` / `template`) to the real service's real path/SQL:
  - `rev-income@1` → `dept-income-rest`, endpoint `/v1/income`.
  - `edu-marks@1` → `dept-marks-soap`, endpoint `/marks/service` + the real SOAP template.
  - `muni-property@1` → `dept-property-sftp`, endpoint `/outbound/property.csv`.
  - `pcb-clearance@1` → `dept-pollution-jdbc`, template = the real `SELECT ... FROM pcb_clearance ...`.
  - `dbt-bank@1` → `dept-bank-rest`, endpoint `/bank`.
- **UPDATE** the affected `catalog_mapping` rows only where the real service's field names differ
  from the mock's (income/marks/property already share field names with the sandbox sources; verify
  each during implementation).
- Sibling mock connectors on the old shared sources (CASTE, LAND, LAND_PARCEL, CROP on Revenue/Agri
  REST mock) are untouched — they keep resolving to mock.

### 3. Config (dev + demo)
Map the new real source codes to the real hosts, mirroring the existing sandbox entries:
- `samanvay.sources.department-service.urls`: `dept-income-rest`, `dept-marks-soap`, `dept-bank-rest`
  → the department-service base URL.
- `samanvay.sources.sftp.sources.dept-property-sftp` → the SFTP host/port/pinned RSA key.
- `samanvay.sources.jdbc.sources.dept-pollution-jdbc` → the department DB JDBC URL.
- SecretStore credentials for `dept-property-sftp` and `dept-pollution-jdbc` (env, same convention as
  the sandbox sources).

### 4. Endpoint/mapping alignment
Each real service's output fields + path must match the repointed connector's `endpoint`/`template`
and `mapping.source` keys. Confirm per category during implementation; adjust the mapping (not the
real service) when a field name differs, so the canonical output the journey expects is unchanged.

## Data flow (shape unchanged, target real)

citizen consent → journey fan-out (`ConnectorRuntime`) → resolve category → **real** connector →
real adapter crosses the network to the independent department (REST/SOAP/SFTP/JDBC) → map → validate
against output schema → audit `DATA_ACCESSED` → journey outcome. No document upload.

## Testing

Because we **replace** mock, journey tests can no longer rely on the in-process backend. Follow the
existing `RealSourcesOnboardingTest` pattern — **real transport against in-process fixtures**, no
Docker in CI:
- REST/SOAP: in-process `HttpServer` fixtures returning the department shapes.
- SFTP: embedded Apache MINA `SshServer` (as `SftpCsvRealTransportTest` does).
- JDBC: embedded H2 with a `pcb_clearance` table (as `JdbcRealTransportTest` does).
- Point the five real sources at these fixtures and assert each journey fetches the real fixture
  values (distinct from the mock's, so a regression to mock is caught).

Update the tests that assert mock values for the five categories, notably `DemoRehearsalIT` and the
per-journey ITs (`FarmerSubsidyJourneyIT`, business-NOC / scholarship ITs). Add a focused test that
the five categories resolve to **REAL** sources (`base_host != mock.samanvay.test`) after V199, and
that the sibling categories stay mock.

Verification commands:
- `./mvnw -o test -Dtest='RealSourcesOnboardingTest,JdbcRealTransportTest,SftpCsvRealTransportTest'`
- `./mvnw -o test -Dit.test=DemoRehearsalIT ... verify` (embedded-real journeys) — CI/Docker where needed.
- Deployed stack: run the three journeys and confirm the five categories hit the real containers.

## Non-goals / consequences

- **CASTE, FIRE_NOC, LAND_RECORD, LAND_PARCEL, CROP stay mock** (the path-to-B backlog; each is a
  later endpoint + repoint, same pattern).
- **Replace, not mode:** after this change the five journey categories **require** the real services
  (compose stack, or CI's embedded fixtures). Pure-offline dev without them will see those five fail.
  The deployed stack runs them, so the live system is unaffected.
- **DigiLocker** remains the only genuinely-external, unbuildable dependency.

## Acceptance criteria

1. `department-service` serves a real `/bank` REST endpoint with a stable fixture shape.
2. `V199` applies cleanly (follows V198); the five journey connectors resolve to real sources
   (`base_host != mock.samanvay.test`); CASTE/FIRE_NOC/LAND_RECORD/LAND_PARCEL/CROP still resolve to
   mock.
3. Through `ConnectorRuntime`, each journey fetches its repointed categories from real services over
   real transport: Scholarship = INCOME+MARKS+BANK; Business-NOC = PROPERTY+POLLUTION; Farmer = BANK.
4. Journey/`DemoRehearsalIT` tests pass against real in-process fixtures (no reliance on the mock
   backend for the five categories); a test asserts the real-vs-mock resolution split.
5. On the deployed stack, the three journeys run against the real `department-service`/`department-db`/
   `department-sftp` containers.
