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
| [docs/architecture/HLD.md](docs/architecture/HLD.md) | High Level Design — architecture, data model, flows, security, build plan |
| `docs/architecture/LLD.md` | Low Level Design — combined *(pending)* |
| `docs/architecture/lld/` | Low Level Design — one document per module *(pending)* |

## Stack

**Backend** Java 21 · Spring Boot 3 · Spring Modulith · PostgreSQL 16 · Flowable (behind a
port) · Keycloak · Resilience4j · Flyway

**Frontend** React 18 · TypeScript · Vite · React Router · TanStack Query · Zod · React
Hook Form

**Runtime** Docker Compose — fully offline, deterministic seed data
