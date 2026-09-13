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
the platform is generic**, not as the product.

## Design principles

| | |
|---|---|
| **P1** | Federated by default, indexed centrally |
| **P2** | The control plane decides, the data plane moves |
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

**Frontend** React 18 · TypeScript · Vite · React Router · TanStack Query · Zod · React
Hook Form

**Runtime** Docker Compose — fully offline, deterministic seed data

## Getting started

Requires: Java 21+ (JDK 25 works fine — Maven compiles down to release 21), Docker Desktop. No Maven install needed, the wrapper handles it.

```bash
docker compose up -d          # starts Postgres
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

Judge/demo console (thin static HTML, not a service portal): `http://localhost:8080/`

Incident kill/revive and audit tamper require demo profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

See [docs/demo/PHASE4_RUNBOOK.md](docs/demo/PHASE4_RUNBOOK.md).

## Contributing

1. Branch off `main`, work in your module's package (`com.samanvay.<module>.*`).
2. Open a PR against `main`. CI must pass; the technical lead's review is required (enforced via [CODEOWNERS](CODEOWNERS) and branch protection — direct pushes to `main` are blocked).
3. Read your module's charter in [`docs/architecture/hld/`](docs/architecture/hld/README.md) before writing code — it defines what your module owns, its public interface, and its acceptance criteria.
