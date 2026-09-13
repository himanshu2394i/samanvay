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
| `docs/architecture/LLD.md` | Low Level Design — combined *(pending)* |
| `docs/architecture/lld/` | Low Level Design — one document per module *(pending)* |

### Modules

| # | Module | Plane | Phase |
|---|---|---|---|
| [01](docs/architecture/hld/01-audit.md) | `audit` | Cross-cutting | 0 |
| [02](docs/architecture/hld/02-catalog.md) | `catalog` | Control | 1 |
| [03](docs/architecture/hld/03-identity.md) | `identity` | Control | 1 → 2 |
| [04](docs/architecture/hld/04-registry.md) | `registry` | Control | 1 → 2 |
| [05](docs/architecture/hld/05-consent.md) | `consent` + `AccessAuthority` | Control | 1 |
| [06](docs/architecture/hld/06-connector.md) | `connector` | Data | 1 → 2 |
| [07](docs/architecture/hld/07-orchestration.md) | `orchestration` | Data | 1 → 2 |
| [08](docs/architecture/hld/08-tracking.md) | `tracking` | Data | 1 |
| [09](docs/architecture/hld/09-notifications.md) | `notifications` | Data | 2 |

## Stack

**Backend** Java 21 · Spring Boot 3 · Spring Modulith · PostgreSQL 16 · Flowable (behind a
port) · Keycloak · Resilience4j · Flyway

**Frontend** React 18 · TypeScript · Vite · React Router · TanStack Query · Zod · React
Hook Form

**Runtime** Docker Compose — fully offline, deterministic seed data

## Getting started

Requires: Java 21+ (JDK 25 works fine — Maven compiles down to release 21), Docker Desktop. No Maven install needed, the wrapper handles it.

```bash
docker compose up -d          # starts Postgres
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

## Contributing

1. Branch off `main`, work in your module's package (`com.samanvay.<module>.*`).
2. Open a PR against `main`. CI must pass; the technical lead's review is required (enforced via [CODEOWNERS](CODEOWNERS) and branch protection — direct pushes to `main` are blocked).
3. Read your module's charter in [`docs/architecture/hld/`](docs/architecture/hld/README.md) before writing code — it defines what your module owns, its public interface, and its acceptance criteria.
