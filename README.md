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

Citizen journeys (scholarship, business licensing, farmer subsidy) exist as **evidence that
the platform is generic**, not as the product .

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

**Frontend** Thin static HTML (government portals + operations cutaways). No React SPA (HLD §11).

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
`GET /ui/auth-config`). Under `dev`/`demo` it becomes the **Dev sign-in** bar with a paste-token
button; other profiles do not serve that script at all. Dev realms are
imported from `keycloak/realms/` (regenerate with `python3 keycloak/gen_realms.py`):

| Realm | Dev users | Sign-in |
|---|---|---|
| `samanvay-staff` | `dev-officer` (department `SCHOLARSHIP`), `dev-reviewer`, `dev-admin`; temporary password `<user>-change-me` | passkey (user verification required) **or** password + TOTP; no password-only path |
| `samanvay-citizen` | `dev-citizen` (no password) | email one-time code (inbox: Mailpit, `http://localhost:8025`) **or** passkey; self-registration without password; direct grants denied |

Department service account `dept-scholarship-dev` (client credentials, secret generated by Keycloak —
read it in the admin console) gets `ROLE_DEPARTMENT` plus one `source:<code>` scope per data source.
Issuers are configurable with `SAMANVAY_STAFF_ISSUER_URI` / `SAMANVAY_CITIZEN_ISSUER_URI`.

Judge path: `http://localhost:8080/` — Maharashtra **Citizen services** (three independent portals).

| URL | What judges see |
|---|---|
| `/` | Citizen services directory |
| `/scholarship/` | Scholarship Portal (Higher Education) |
| `/licence/` | Business licence / NOC (Industries) |
| `/farmer/` | Farmer subsidy (Agriculture) — catalog journey, caller skin |
| `/demo.html` | Optional Samanvay operations cutaway |

Spoken eight-minute walk: [docs/demo/JUDGE_SCRIPT.md](docs/demo/JUDGE_SCRIPT.md). One-pager: [docs/demo/ONE_PAGER.md](docs/demo/ONE_PAGER.md). Technical rehearsal: [docs/demo/PHASE4_RUNBOOK.md](docs/demo/PHASE4_RUNBOOK.md).

Officer desk (all portals): the in-page `officer` / `demo-2026` form only opens the desk view; the API calls behind it need a staff-realm token (Dev sign-in bar, realm "staff"). Revenue unavailable/restore and audit tamper need the `demo` profile.

Samanvay remains the interoperability middle layer. The portals are callers, not the product.

## Department simulators (dev/CI only)

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

### Standalone department service: real REST and SOAP over the network (dev/demo only)

The same `simulators/` app also serves a fake "sandbox department" so the connector's **real** `RestAdapter`
and `SoapAdapter` can be shown fetching from a separate networked service, not the in-process
`MockDepartmentBackend`. It serves invented data only and is never a real government system.

| Protocol | Endpoint | Answer |
|---|---|---|
| REST | `GET /v1/income?rationCard=RC-1001` | JSON `annualIncome`, `holderName`, `district`, ... |
| SOAP 1.1 | `POST /marks/service` (`text/xml`, body carries `<studentId>`) | `GetMarksResponse` with `percentage`, `board`, `exam`; a bad request gets a SOAP `Fault` (HTTP 500) |

Named fixtures: `RC-1001`, `RC-1002`, `S-1001`, `S-1002`. Any other id gets a record derived from the id, so a
given id always answers the same.

```bash
docker compose up -d                     # postgres, keycloak, mailpit and department-service (:8090)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo   # or: dev
curl 'localhost:8090/v1/income?rationCard=RC-1001'
curl -H 'Content-Type: text/xml' -d '<Envelope><Body><GetMarks><studentId>S-1001</studentId></GetMarks></Body></Envelope>' \
  localhost:8090/marks/service
```

Under the `dev` or `demo` profile, `application-dev.yml` / `application-demo.yml` set
`samanvay.sources.department-service.urls`, which points the V193 sandbox sources (`sandbox-income-rest`,
`sandbox-marks-soap`, seeded with unresolvable `*.example` hosts) at `http://localhost:8090`
(`SAMANVAY_DEPT_SERVICE_URL` changes it). A fetch through `ConnectorRuntimeImpl` for those sources is then a
real HTTP exchange with the service. Nothing else changes: the mock sources and the real journeys' sources
keep their hosts, the setting is empty by default, and the app refuses to start with it set under any other
profile. Without Docker: `./mvnw -f simulators/pom.xml spring-boot:run`.

`StandaloneDepartmentServiceTest` is the proof: it builds and starts the service as its own JVM process on a free
port and runs both sandbox connectors through the real adapters, asserting values only that service produces.

## Contributing

1. Branch off `main`, work in your module's package (`com.samanvay.<module>.*`).
2. Open a PR against `main`. CI must pass; the technical lead's review is required (enforced via [CODEOWNERS](CODEOWNERS) and branch protection — direct pushes to `main` are blocked).
3. Read your module's charter in [`docs/architecture/hld/`](docs/architecture/hld/README.md) before writing code — it defines what your module owns, its public interface, and its acceptance criteria.
