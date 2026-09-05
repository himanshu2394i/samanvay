# Samanvay — High Level Design

**Government Digital Platform Interoperability & Federated Service Delivery Platform**

| | |
|---|---|
| **Problem Statement** | SIH26129 — *System integration and interoperability among government digital platforms, resulting in fragmented service delivery* |
| **Organisation** | Government of Maharashtra — Maharashtra State Innovation Society |
| **Category / Theme** | Software / Miscellaneous |
| **Document** | High Level Design (HLD) |
| **Status** | Approved — frozen for LLD |
| **Date** | 2026-09-05 |

---

## Table of Contents

1. [Problem and Thesis](#1-problem-and-thesis)
2. [Design Principles](#2-design-principles)
3. [System Architecture](#3-system-architecture)
4. [Technology Decisions](#4-technology-decisions)
5. [Data Model](#5-data-model)
6. [Core Flows](#6-core-flows)
7. [Connector Subsystem](#7-connector-subsystem)
8. [Department Onboarding](#8-department-onboarding)
9. [Security Architecture](#9-security-architecture)
10. [Observability](#10-observability)
11. [Frontend Architecture](#11-frontend-architecture)
12. [Deployment](#12-deployment)
13. [Journeys and Mock Departments](#13-journeys-and-mock-departments)
14. [Build Plan](#14-build-plan)
15. [Risks](#15-risks)
16. [Demo Script](#16-demo-script)
17. [Glossary](#17-glossary)

---

## 1. Problem and Thesis

### 1.1 The problem, restated

Government departments in Maharashtra operate independently-built portals, mobile
applications, registries, workflow systems and databases. They differ in data formats,
identifiers, authentication methods, APIs, process definitions and ownership.

The consequences are concrete:

- Citizens submit the same information repeatedly to different departments.
- Citizens track a single life event across several unrelated portals.
- Officials have no consolidated view of a beneficiary, their applications or outcomes.
- Departments cannot coordinate approvals that legitimately span organisational lines.

### 1.2 The constraint that defines the solution

> *"The challenge is to enable secure, standards-based interoperability **without requiring
> complete replacement of existing systems**."*

This single clause removes the obvious answer. We are not permitted to build one unified
portal and migrate everyone onto it. We must build a layer that sits **between** systems
that already exist, that will not change, and that in several cases have no API at all.

### 1.3 Thesis

> **The platform does not centralize government data. It makes distributed government data
> discoverable, accessible and interoperable under explicit authorization.**

Samanvay (समन्वय, *"coordination"*) is an interoperability platform, not a service portal.
Citizen journeys — scholarship, business licensing, farmer subsidy — are **evidence that
the platform is generic**, not the product itself.

### 1.4 What the problem statement asks for, mapped to this design

| Requirement in PS | Where it is addressed |
|---|---|
| API-based exchange | §7 Connector Subsystem |
| Common data standards | §5.1 Canonical Model |
| Master data management | §5.3 Identity Linking & Resolution |
| Consent-based data sharing | §5.5 Consent, §6.1 Consent-gated fetch |
| Single sign-on / federated identity | §9.2 Keycloak + department IdP brokering |
| Event-driven notifications | §3.3 `notifications` module, Modulith outbox |
| Unified application tracking | §5.7 Tracking |
| Configurable workflow orchestration | §7.1, §3.3 `orchestration` (Flowable behind a port) |
| Reusable connectors for legacy and modern systems | §7.2 Protocol Adapter SPI |
| Audit logs | §5.8, §9.4 Tamper-evident chain |
| Role-based access | §9.2 Three authorization axes |
| Data quality checks | §7.3 Execution pipeline |
| Exception handling | §6.2 Degraded mode, §7.4 Batch exception queue |
| Monitoring dashboards | §10 Observability |

### 1.5 Scope fence — deliberately not built

Written down so that scope does not drift during implementation:

- Not a replacement for Aaple Sarkar or any citizen-facing service portal
- No blockchain, anywhere
- No LLM in the request path (see §7.6)
- No real Aadhaar integration — tokenized references only
- No Kubernetes, no HA topology, no multi-region
- No mobile application
- No real payment rails — DBT is mocked
- We do not build department systems; we build deliberately ugly **mocks** of them

---

## 2. Design Principles

Six principles. Every design decision in this document derives from one of them, and any
future decision that violates one requires an explicit amendment to this section.

### P1 — Federated by default, indexed centrally

Government data stays with the department that owns it. Samanvay stores **identity links,
consent artifacts and discovery metadata** — never the underlying data. Payloads are
fetched on demand, under authorization, and are never persisted.

*Inspired by* India's consent-based, federated data-sharing model used in the Account
Aggregator ecosystem, where the consent manager facilitates exchange without storing or
processing the data. We generalise that principle from financial data to government
services; we do not claim to implement the AA specification.

### P2 — The control plane decides, the data plane moves

Authorization, identity, consent, discovery and configuration are **decisions**: slow,
transactional, audited, rarely changing. Orchestration, connectors and delivery are
**movement**: fast, retried, idempotent, failure-prone.

They are separated, and the invariant is structural rather than procedural:

> **No data moves in the data plane without a signed authorization issued by the control
> plane.** The connector runtime takes an `AccessGrant` as a required argument. There is no
> code path that fetches without one.

### P3 — Machines propose, humans dispose, the decision is audited

Applies identically to identity match scoring and to field-mapping suggestion. Automated
inference may **propose**; only a human may **commit**; the commitment is audited with the
proposer's confidence recorded.

A probabilistic identity match never becomes an active link automatically, at any
confidence score. Wrong merges in a benefits system cause real harm to real people.

### P4 — The canonical model is a transport contract, not ownership

The canonical schema exists so that heterogeneous systems can exchange meaning. It does
**not** make Samanvay a source of truth. Two consequences:

1. Canonical payloads are never persisted. Caching one "for performance" silently makes
   the platform a system of record and invalidates §5.9.
2. Every canonical object carries `_provenance` — source department, `as_of`, connector
   version, grant id — so no value displayed anywhere is orphaned from its origin.

### P5 — Concrete, then generalize, then prove

Journey 1 is built concretely through a deliberately thin layer. Journey 2 reveals where
the real seams are; generalization happens **there**, on evidence. Journey 3 must then
require zero core code — and that claim is verifiable with `git diff`.

Designing the abstraction first produces a framework fitted to imagined requirements,
which the first real department then breaks.

### P6 — Depend on capabilities only where a swap is real

An interface with one implementation and no plausible second one is a file you must open
before reaching the code that matters. Exactly three ports exist:

| Port | Justification |
|---|---|
| `WorkflowEngine` → Flowable | Highest-risk dependency; deliberate escape hatch |
| `ProtocolAdapter` → REST / SOAP / SFTP / JDBC | Four real implementations on day one |
| `SecretStore` → env-encrypted / Vault | Genuine dev↔prod swap |

Explicitly **not** ports: `IdentityProvider` (Spring Security already abstracts Keycloak),
`AuditStore` (the JPA repository is the port), `EventPublisher` (`ApplicationEventPublisher`
is Spring's stable interface).

---

## 3. System Architecture

### 3.1 System context

```
┌────────────────────────────────────────────────────────────────────┐
│  SURFACES        Citizen  │  Officer  │  Integration Admin         │
│                  (one React application, role-scoped routing)      │
└─────────────────────────────┬──────────────────────────────────────┘
                              │  OIDC (Keycloak)
┌─────────────────────────────▼──────────────────────────────────────┐
│  Reverse proxy — TLS termination, static assets                    │
└─────────────────────────────┬──────────────────────────────────────┘
                              │
┌─────────────────────────────▼──────────────────────────────────────┐
│  SAMANVAY CORE  —  one Spring Boot deployable                      │
│                                                                    │
│  API boundary: Spring Security filter chain                        │
│  authN · authZ · correlation id · rate limiting · audit tap        │
│  ──────────────────────────────────────────────────────────────────│
│                                                                    │
│  ── CONTROL PLANE ──────────────   ── DATA PLANE ───────────────── │
│                                                                    │
│   identity    Linking & Resolution  orchestration  WorkflowEngine  │
│   consent     Grant · verify        connector      Adapters +      │
│               · revoke                             mapping runtime │
│   registry    What exists where     tracking       Unified status  │
│               (metadata only)                      + SLA timers    │
│   catalog     Departments ·         notifications  Subscriptions   │
│               connectors ·                         + delivery      │
│               schemas · mappings                                   │
│                                                                    │
│  ── CROSS-CUTTING ────────────────────────────────────────────────│
│   audit (hash-chained, signed)  ·  data-quality  ·  metrics        │
│   event publication (Spring Modulith publication registry)         │
└─────────────────────────────┬──────────────────────────────────────┘
                              │  fetch on demand — payload never persisted
      ┌──────────────┬────────┴─────┬──────────────┬─────────────────┐
      ▼              ▼              ▼              ▼                 ▼
  Revenue        Education      Municipal      Pollution           DBT
  SOAP / XML     REST + key     CSV / SFTP     no API →         REST +
  own IdP        ugly schema    batch only     direct JDBC      OAuth2
```

**There is no separate API Gateway deployable.** Edge concerns (authentication,
authorization, correlation IDs, rate limiting via Bucket4j) live in the Spring Security
filter chain, in a single named package so extraction remains cheap if ever justified.
Introducing a second deployable for a capability the framework already provides would buy
operational cost with no architectural gain.

### 3.2 Modular monolith

One deployable, hard internal module boundaries, enforced by **Spring Modulith**.

Rationale:

- The architecture diagram is identical to a microservice decomposition; the modules
  simply share a process.
- Transactions span consent, audit and registry naturally. In a distributed version each
  of these becomes a saga.
- One process to run, one log to read, one artifact to deploy.
- `ApplicationModules.of(SamanvayApplication.class).verify()` runs in CI and **fails the
  build** on a boundary violation. Architecture not enforced by CI is only a diagram —
  this matters more, not less, with six developers of mixed experience.
- Spring Modulith's **Event Publication Registry is a transactional outbox**: an event is
  persisted in the same transaction as the business write, delivered, then marked
  complete, with incomplete publications retried on restart. This is the event-driven
  backbone. No Kafka is required, and no additional infrastructure can be down during a
  demo.

Extraction path, when load justifies it: each module owns its own tables and communicates
through interfaces and events, so any module can be lifted into a service without changing
its callers.

### 3.3 Modules

Each module is a Spring Modulith module: its own package, its own tables, its own owner,
communicating only through interfaces and events.

> **Each module has a charter document in [`hld/`](hld/README.md)** covering its
> responsibilities, public interface, owned data, events, key decisions, failure modes and
> acceptance criteria. Those documents defer to this one: if a module charter contradicts
> the combined HLD on principles, flows or technology, the combined HLD wins.

| Module | Owns | Plane |
|---|---|---|
| `identity` | Citizen master record, department links, provenance, matching engine, review queue | Control |
| `consent` | Consent artifacts — purpose, categories, requester, validity, revocation | Control |
| `registry` | Discovery index: what data exists where, validity, freshness, sensitivity | Control |
| `catalog` | Departments, data sources, connectors, canonical schemas, mappings, journeys | Control |
| `connector` | Adapter runtime — protocol adapters, mapping execution, resilience | Data |
| `orchestration` | Journey definitions, parallel fan-out, degraded mode, compensation | Data |
| `tracking` | Citizen-facing reference, cross-department status, SLA timers | Data |
| `notifications` | Subscriptions, channel preferences, templates, delivery attempts | Data |
| `audit` | Tamper-evident chain, checkpoint signing, verification | Cross-cutting |

**Data quality is a library, not a module** — it holds no state of its own and is used by
`connector` (inbound validation) and `registry` (conformance). Making it a module would be
an abstraction with one consumer shape and no independent lifecycle.

**Event publication is infrastructure, not a domain module.** Domain modules publish
events; Modulith's registry delivers them. `notifications` is a domain module because
subscriptions, channel preferences and delivery attempts are genuine domain state.

**`AccessAuthority` is a control-plane facade, not a tenth module.** It composes
`identity`, `consent` and `registry` into the single decision described in §6.1 and issues
the signed `AccessGrant`. It lives in the `consent` module and is owned by the technical
lead, because it is the one place where the P2 invariant is enforced.

### 3.4 Data ownership

The answer to *"whose data is this?"*, which is the first question a government evaluator
asks:

| Resource | System of record | Samanvay holds |
|---|---|---|
| Income / caste certificate | Revenue Department | Pointer + provenance |
| Marks, enrolment | Education Board | Pointer + provenance |
| Property record, trade licence | Municipal Corporation | Pointer + freshness (batch) |
| Pollution NOC | Pollution Control Board | Pointer + provenance |
| Bank / DBT mandate | DBT | Pointer only |
| **Identity links** | **Samanvay** | Authoritative |
| **Consent artifacts** | **Samanvay** | Authoritative |
| **Discovery metadata** | **Samanvay** | Authoritative |
| **Workflow and tracking state** | **Samanvay** | Authoritative |
| **Audit chain** | **Samanvay** | Authoritative |
| **Schemas and mappings** | **Samanvay** | Authoritative |

Samanvay owns six things. Everything else it merely knows the address of.

---

## 4. Technology Decisions

| Decision | Choice | Rationale | Rejected |
|---|---|---|---|
| Backend | Java 21 + Spring Boot 3 | Government-standard stack; Spring Modulith enforces boundaries at build time | Node, Python, Go |
| Module enforcement | Spring Modulith | Build-time verification + transactional outbox for free | Convention + code review |
| Database | PostgreSQL 16 | JSONB for schemas/mappings, `FOR UPDATE SKIP LOCKED` for queues, one dependency | Separate document store |
| Messaging | Modulith Event Publication Registry | Durable, transactional, zero extra infrastructure | Kafka, RabbitMQ |
| Workflow | Flowable, embedded, **behind `WorkflowEngine`** | BPMN 2.0 is standards-based; parallel gateways, timers, retries, history included. The port caps the risk | Custom DAG executor, Spring State Machine |
| Identity | Keycloak + per-department mock IdPs | OIDC, SAML, identity brokering, realms, RBAC. Brokering demonstrates genuine cross-organisation SSO | Hand-rolled JWT auth |
| Resilience | Resilience4j | Declarative timeout, retry, circuit breaker, bulkhead | Hand-rolled |
| Batch | Chunked job over a job table | A few thousand rows nightly does not justify a framework | Spring Batch |
| Frontend | React + TypeScript + Vite | Component model for dashboards and wizards; no SSR/SEO need | Next.js, Angular |
| Frontend data | TanStack Query + Zod + React Hook Form | Caching/retry/invalidation; runtime validation of API responses; complex connector-mapping forms | Redux, plain fetch |
| Migrations | Flyway | Versioned, reviewable, ordinary | Hibernate auto-DDL |
| Secrets | `SecretStore` port — Jasypt (dev) / Vault (prod) | Real dev↔prod swap; credentials never in `catalog_*` | Encrypted column only |

---

## 5. Data Model

### 5.1 Canonical model — data, not Java classes

The canonical model is stored in `catalog_schema` as **JSON Schema**, and the runtime
operates on a validated `JsonNode`. Typed Java records exist only where business logic
requires field access.

> **Dynamic at the interoperability boundary; strongly typed inside domain logic.**
> The application must not become `JsonNode` soup.

If the canonical model existed only as compiled classes, changing the canonical contract
would require a redeploy — which makes runtime department onboarding impossible.

Six canonical entities. The introduction order is deliberate and is itself the proof of P5:

| Entity | Introduced | Note |
|---|---|---|
| `Person` | Journey 1 | Core |
| `Address` | Journey 1 | Shared |
| `Credential` | Journey 1 | Envelope for any attestation |
| `Application` | Journey 1 | A service request |
| `Organisation` | Journey 2 | Business NOC |
| `LandParcel` | **Journey 3** | Added as a schema row — **zero core code** |

#### `Person`

```jsonc
{
  "name": {
    "latin":       "Ramesh Kumar Patil",
    "devanagari":  "रमेश कुमार पाटील",
    "given": "Ramesh", "middle": "Kumar", "family": "Patil",
    "father_name": "Kumar Patil"
  },
  "dob":           "1998-06-14",
  "dob_precision": "DAY",            // DAY | MONTH | YEAR
  "gender":        "M",
  "identifiers": [
    { "type": "RATION_CARD", "issuer": "REVENUE_MH", "value_token": "…" },
    { "type": "BOARD_ROLL",  "issuer": "MSBSHSE",    "value_token": "…" }
  ]
}
```

Two fields exist for domain reasons, not completeness:

- **`dob_precision`** — a large share of legacy Indian government records carry only a
  birth year. An exact-date match rule silently fails against them.
- **Dual-script names** — Maharashtra records arrive in Devanagari and Latin. Treating
  `रमेश` and `Ramesh` as different people is the single most common source of duplicate
  beneficiary records.
- **`father_name`** — the de-facto matching key in rural records that predate stable IDs.

Both are trivial to model now and expensive to retrofit.

**Identifier constraint:** identifiers are stored as `value_token`, never as raw values.
Aadhaar numbers in particular carry statutory storage restrictions; keeping only tokens and
issuer references from day one avoids a compliance retrofit.

#### `Credential` is an envelope, not a god-object

Fixed metadata plus a schema-validated payload:

```jsonc
{
  "id": "…", "type": "INCOME_CERTIFICATE",
  "issuer": "REVENUE_MH",
  "issued_at": "2024-03-11", "valid_until": "2027-03-10",
  "subject_ref": "…",
  "payload_schema_ref": "IncomeCertificate@1",
  "payload": { "annual_income": 180000, "financial_year": "2023-24" }
}
```

New credential types (`Marksheet@2`, `TradeLicence@1`, `LandRecord@1`) are schema rows, not
Java classes.

#### Provenance sidecar

Every canonical object returned to a consumer carries origin metadata:

```jsonc
{
  "person": { "dob": "1998-06-14", "annual_income": 180000 },
  "_provenance": {
    "dob":           { "source": "REVENUE_MH", "as_of": "2026-09-05T11:04Z",
                       "connector": "rev-income@3", "grant": "…", "freshness": "REALTIME" },
    "annual_income": { "source": "REVENUE_MH", "as_of": "2026-09-05T11:04Z",
                       "connector": "rev-income@3", "grant": "…", "freshness": "REALTIME" }
  }
}
```

An officer sees *"income ₹1,80,000 — Revenue Department, 2 minutes ago"*, not a number of
unknown parentage. This is what makes P4 visible rather than merely asserted.

### 5.2 Mapping DSL — deliberately not a programming language

```jsonc
{
  "source":    "$.GetIncomeCertResponse.applicant.dtOfBirth",
  "target":    "person.dob",
  "transform": [ { "fn": "date_parse", "args": ["dd-MM-yyyy"] } ]
}
```

Source is JSONPath; XML and CSV are normalized to JSON first.

| Allowed transforms (fixed registry) | Explicitly forbidden |
|---|---|
| `trim`, `upper`, `lower`, `date_parse`, `coalesce`, `split_name`, `lookup`, `mask` | `eval`, script execution, expression languages, loops, HTTP calls, database queries, file access |

A mapping language that cannot loop cannot hang, cannot be injected into, and cannot be
used to exfiltrate. When an unsupported transformation is needed, one named function is
added to the registry — a small reviewable change — rather than embedding a scripting
engine that then requires a sandbox.

### 5.3 Identity — linking and resolution

Two distinct jobs with different failure modes:

- **Identity Linking** — the citizen proves that two departmental IDs belong to them.
  Fails safe: no link is created.
- **Identity Resolution** — the system finds *possible* relationships between existing
  records. Fails dangerous: a wrong human is merged.

```
identity_citizen ──1:1──► identity_profile        (citizen-provided, minimal)
       │
       ├──1:N──► identity_link ──────► catalog_department
       │
       └──1:N──► identity_match_key   (blocking keys, hashed)

identity_candidate_match   — NOT a link. Awaits human review.
```

| Table | Holds |
|---|---|
| `identity_citizen` | Master record: UUID, status. Nothing else |
| `identity_profile` | Name variants, DOB + precision, gender, masked contact — **supplied by the citizen at registration**, therefore consented by construction |
| `identity_link` | `citizen_id`, `department_id`, `local_id_type`, `local_id_token`, `provenance`, `confidence`, `verified_at`, `status` |
| `identity_match_key` | Hashed blocking keys (`metaphone(family_name)+birth_year`, `pincode+dob`) for candidate generation |
| `identity_candidate_match` | `score`, `features` (jsonb), `status`, `reviewed_by`, `reviewed_at` |

`provenance` ∈ `CITIZEN_ASSERTED | DETERMINISTIC | PROBABILISTIC | OFFICER_CONFIRMED`.
Every downstream decision can see *how* a link came to exist.

#### The safety invariant

> A `PROBABILISTIC` match never becomes an active link automatically, at any confidence
> score. Only citizen assertion or explicit officer confirmation creates one.

Enforced at three independent levels, so that a service-layer bug cannot produce the
dangerous state:

1. **Service layer** — the linking service refuses the transition.
2. **Database** — `CHECK (status <> 'ACTIVE' OR provenance <> 'PROBABILISTIC')`.
3. **Authorization** — only `IDENTITY_REVIEWER` may promote a candidate, and the promotion
   is audited.

The platform stores **no department attribute data** for matching. Resolution fetches
department records transiently under an admin-purpose grant, scores them against the
citizen-asserted profile, and retains only the score and feature vector.

### 5.4 Registry — pointers, with metadata leakage closed

```
registry_pointer
├─ subject_id, subject_type        PERSON | ORGANISATION | LAND_PARCEL
├─ department_id, data_category
├─ sensitivity                     PUBLIC | RESTRICTED | SENSITIVE
├─ discovery_policy                VISIBLE | CONSENT_REQUIRED_TO_DISCOVER
├─ source_ref                      opaque locator, e.g. {"endpoint":"…","key":"…"}
├─ issued_at, valid_until
├─ as_of, freshness_mode           REALTIME | BATCH
└─ status                          AVAILABLE | EXPIRED | WITHDRAWN
```

**Metadata is not neutral.** A pointer reading
`Citizen X → Social Justice → Caste Certificate` leaks caste without exposing the
certificate. The same is true of disability, health and legal-proceeding categories.
"We store only metadata" is therefore not sufficient protection on its own.

Three mitigations:

1. **Discovery is an authorized operation.** A department sees only pointers in categories
   it is entitled to.
2. **Discovery reads are audited**, exactly as data fetches are.
3. Categories marked `SENSITIVE` default to `CONSENT_REQUIRED_TO_DISCOVER` — the pointer is
   invisible until the citizen authorizes that requester to learn it exists.

### 5.5 Consent and access grants

Long-lived permission, short-lived capability. These are different concepts with different
lifecycles and are modelled separately.

```
consent_artifact                          access_grant
├─ subject_citizen_id                     ├─ id                UUID (audit joins here)
├─ requester_id                           ├─ nonce             256-bit, UNIQUE
├─ purpose_code, purpose_text             ├─ consent_id, consent_version
├─ data_categories []                     ├─ subject, requester, data_category
├─ granularity  ONE_TIME | RECURRING      ├─ department_id, connector_id
├─ valid_from, valid_until                ├─ issued_at, expires_at   (+60s)
├─ frequency_limit                        ├─ used_at                 (burns the nonce)
├─ status, version                        └─ signature
└─ citizen_auth_ref                       
```

- **`citizen_auth_ref`** stores the Keycloak token identifier of the session that granted
  consent. This converts *"a row says they consented"* into *"we can prove this
  authenticated session granted it"*.
- **`consent_version`** increments on any change. A grant carrying version 3 fails
  verification the moment the artifact reaches version 4 — this is how mid-flight
  revocation (§6.3) takes effect, via a field comparison rather than cache invalidation.
- **`id` ≠ `nonce`.** The grant id answers *"which authorization was issued?"*; the nonce
  answers *"has this capability already been used?"* Audit joins on the id; single-use
  enforcement burns the nonce.
- **Who signs.** The grant is signed by the control plane using a key resolved from the
  `SecretStore` — the same mechanism as audit checkpoint signing (§9.4), a different key.
  The connector runtime holds only the public key, so it can verify a grant but cannot
  mint one.

Grants are metadata and are persisted for nonce enforcement and audit, then archived after
90 days.

### 5.6 Catalog

`catalog_department`, `catalog_data_source`, `catalog_connector`, `catalog_schema`,
`catalog_mapping`, `catalog_journey`.

- Connectors and mappings are **versioned**. A live connector is never edited; a new
  version is published and journeys migrate.
- `catalog_journey` holds the BPMN reference, required data categories, and policy such as
  `accept_stale`.
- Department credentials are **never** stored in `catalog_*`. `auth_config_ref` points at
  the `SecretStore`.

### 5.7 Tracking

```
tracking_application                       tracking_step
├─ id                                      ├─ application_id
├─ reference_no   MH-SCH-2026-000123       ├─ step_code, department_id
├─ citizen_id, journey_code                ├─ status, outcome
├─ process_instance_id  (Flowable)         ├─ started_at, completed_at, sla_due_at
├─ status, submitted_at                    └─ source  API | BATCH | CITIZEN_UPLOAD
└─ sla_due_at, closed_at
```

`tracking_step.source` gives per-step provenance, so an approving officer can see how each
piece of evidence in front of them was obtained.

### 5.8 Audit

Own PostgreSQL schema, own database role.

```
audit_entry                                audit_checkpoint
├─ seq  bigserial                          ├─ seq
├─ ts, actor_type, actor_id                ├─ root_hash
├─ action, subject_id, resource            ├─ signed_at
├─ department_id, consent_id, grant_id     ├─ signature
├─ outcome, reason, meta (jsonb)           └─ published_ref
├─ prev_hash
└─ hash
```

### 5.9 What is stored, and what is never stored

| Stored | Never stored |
|---|---|
| Citizen-asserted profile (name, DOB, gender, contact) | Certificate or document payloads |
| Identity links and provenance | Department attribute data |
| Discovery pointers: existence, validity, freshness | Raw Aadhaar or strong identifier values |
| Consent artifacts and access grants | Fetched payloads, at any point |
| Audit chain | Canonical objects (P4) |
| Application status and SLA timers | |
| Schemas, mappings, connector definitions | |

**One disclosed exception.** Citizen manual uploads (§6.2 fallback) *are* stored —
encrypted at rest in object storage, retention bound to the application lifecycle, purged
on closure, and marked `SOURCE: CITIZEN_UPLOAD` everywhere they surface.

---

## 6. Core Flows

### 6.1 Consent-gated fetch — the spine

```
Workflow step: "income certificate required for citizen C"
        │
        ▼
  AccessAuthority                      ← identity + consent + registry, together
        │
        ├── identity : is there an ACTIVE LINK for C at Revenue?
        ├── consent  : active artifact covering (requester, category, purpose)?
        ├── registry : pointer exists? fresh enough? requester cleared for
        │              this sensitivity class?
        │
        ├── any NO ──────► DENY   (reason recorded, audited)
        │
        └── all YES ─────► issue signed ACCESS GRANT
                           { id, nonce, subject, requester, category,
                             source, purpose, consent_id, consent_version,
                             exp: +60s }
                                    │
                                    ▼
                          ConnectorRuntime
                          verify: signature · expiry · nonce unused
                                · category matches declaration
                                    │
                     no valid grant ──► no code path exists to call out
                                    │
                                    ▼
                            Department system
```

The payload flows department → adapter → normalize → data quality → mapping → workflow
variable, **in memory**. What is written is a registry freshness update and two audit
entries (`GRANT_ISSUED`, `DATA_ACCESSED`). That asymmetry is the entire privacy claim, and
it is visible in the code.

### 6.2 Journey execution — parallel fan-out and degraded mode

```
                    ┌─── Revenue  / income  ──── ok    800ms
   ┌────────────┐   ├─── Revenue  / caste   ──── ok    900ms
   │  PARALLEL  │───┤
   │  GATEWAY   │   ├─── Education/ marks   ──── ok   1200ms
   └────────────┘   └─── DBT      / bank    ──── TIMEOUT
                                                    │
                        ┌───────────────────────────┘
                        ▼
              step        → PENDING_SOURCE
              application → PARTIALLY_VERIFIED       (not FAILED)
                        │
                        ├─► retry with exponential backoff (Flowable timer)
                        ├─► circuit breaker opens after N failures
                        ├─► officer sees it in the exception queue
                        └─► if SLA breached → citizen may upload the
                                              document manually
```

Three deliberate decisions:

- **`PARTIALLY_VERIFIED` is a state, not an error.** The application continues through
  steps that do not depend on the missing input. One department being down must not halt a
  citizen's file — that is precisely the fragmentation the problem statement describes,
  and reproducing it inside the platform would be a design failure.
- **The manual-upload fallback is the honest path.** It is what happens in a real
  government office when a system is down. It is audited differently
  (`SOURCE: CITIZEN_UPLOAD` vs `SOURCE: REVENUE_API`), so the provenance of every field
  remains visible to the approving officer.
- **Retries are Flowable timers, not cron jobs.** They survive restart, are visible in the
  process instance, and need no separate scheduler.

### 6.3 Mid-flight consent revocation

```
Citizen revokes  ──►  consent_artifact.status = REVOKED
                      version++ , ConsentRevoked event
                                  │
     ┌────────────────────────────┼────────────────────────────┐
     ▼                            ▼                            ▼
in-flight grants          workflow steps →            audit entry
fail verification         AUTHORIZATION_WITHDRAWN     CONSENT_REVOKED
(consent version          officer notified            (chained, signed)
 mismatch)
```

> **There is no data to purge, because none was ever stored.** Revocation takes effect on
> the next access attempt — within 60 seconds, being the grant TTL.

In a central-hub architecture revocation is a deletion job that someone must be trusted to
run. Here it is a structural consequence of P1.

### 6.4 Batch departments — freshness as data

Municipal can supply only a nightly CSV. Most designs pretend this away.

```
02:00  SFTP poll ──► checksum (seen before? stop) ──► stream rows
                                                          │
                                        ┌─────────────────┴──────────────┐
                                        ▼                                ▼
                                 rows that pass                  rows that fail
                                        │                                │
                          registry pointers upserted            exception queue
                          with as_of = file timestamp           (officer resolves)
                                        │
   ── later, at request time ───────────┘
                                        ▼
       registry returns: pointer + as_of + freshness = STALE (14h)
                                        │
                 journey policy (configuration, not code) decides:
                   ├── accept_stale: true   → proceed, flag on the record
                   └── accept_stale: false  → route to manual verification
```

Two payoffs. Freshness becomes **data rather than an assumption** — the officer sees
"property record as of 02:00 today". And `accept_stale` is per-journey configuration, so
Journey 3 may hold a different tolerance from Journey 1 with no code change.

---

## 7. Connector Subsystem

The extensibility of the entire platform lives here. A **connector** describes *what
government resource is being accessed*; a **protocol adapter** describes *how to
communicate with it*. That distinction is what allows a new department on an existing
protocol to cost configuration only.

```
                    CATALOG
                       │
                       ▼
              ConnectorDefinition
                       │
                       ▼
                ConnectorRuntime
        ┌──────────────┼──────────────┐
        │              │              │
   AccessGrant    InputBinding    Resilience
   verification    + Mapping       + Timeout
        └──────────────┼──────────────┘
                       ▼
                ProtocolAdapter
          ┌────────────┼────────────┬────────────┐
          ▼            ▼            ▼            ▼
        REST         SOAP        SFTP/CSV      JDBC
          └────────────┴────────────┴────────────┘
                       ▼
              Raw department data
                       ▼
                 Normalize → JSON
                       ▼
                  Data quality
                       ▼
                  Mapping DSL
                       ▼
              Canonical JSON Schema
                       ▼
                  + Provenance
                       ▼
                    JsonNode  ⟶  Workflow
```

### 7.1 A connector is data

```jsonc
{
  "id": "rev-income", "version": 3, "status": "PUBLISHED",
  "data_source": "revenue-soap-prod",     // protocol + base URL + auth live here
  "data_category": "INCOME_CERTIFICATE",
  "freshness_mode": "REALTIME",

  "capabilities": {
    "FETCH":  { "endpoint": "getIncomeCert",
                "template": "<soap:Envelope>…<rationCard>{{rationCard}}</rationCard>…</soap:Envelope>",
                "mapping_ref": "map-rev-income@3",
                "output_schema": "Credential/IncomeCertificate@1" },
    "VERIFY": { "endpoint": "verifyIncomeCert",
                "template": "…",
                "mapping_ref": "map-rev-income-verify@1",
                "output_schema": "VerificationResult@1" }
  },

  "inputs": [
    { "name": "rationCard", "from": "link.local_id_token", "required": true },
    { "name": "fy",         "from": "journey.var.financialYear" }
  ],
  "response": { "root": "$.Body.GetIncomeCertResponse",
                "error_paths": ["$.Body.Fault", "$..[?(@.status=='ERR')]"] },

  "sla_ms": 3000,
  "retry":   { "max": 3, "backoff": "exponential", "base_ms": 500 },
  "breaker": { "failure_rate": 50, "window": 20, "open_seconds": 30 }
}
```

**Capabilities are `FETCH | VERIFY | SUBMIT`.** `SUBSCRIBE` (department→platform push) is
deliberately excluded until a journey requires it. Capabilities are a **map**, because each
capability needs its own endpoint, template, mapping and output schema.

Orchestration never knows what a Revenue connector is. It asks `connector.supports(FETCH)`.

**`inputs[].from` is a binding expression over a fixed context** — `link.*`, `profile.*`,
`grant.*`, `journey.var.*`. It is not an expression language. A connector cannot address
data that is not already in scope for the current grant.

### 7.2 Protocol Adapter SPI

```java
public interface ProtocolAdapter {
    String protocol();                            // REST | SOAP | SFTP_CSV | JDBC
    AdapterResponse execute(AdapterRequest req);  // raw bytes + transport metadata
}
```

| Adapter | Built on | Non-negotiable detail |
|---|---|---|
| `RestAdapter` | `WebClient` | URL-encode bound parameters; never concatenate into a path |
| `SoapAdapter` | `WebClient` + XML | **XML-escape every substitution.** A name containing `&` breaks the envelope; a crafted one rewrites it. DTDs and external entities disabled |
| `SftpCsvAdapter` | Apache MINA SSHD | Host-key verification enabled; checksum files to detect re-delivery |
| `JdbcAdapter` | Read-only `DataSource` | Bound parameters only, `SELECT`-only statement guard, row-count cap, dedicated read-only user at the department |

Adding a department that uses an existing protocol requires **no new Java** — only a
connector definition. New Java is written only for a genuinely new communication protocol.
That is the precise definition of the extensibility being claimed.

### 7.3 Execution pipeline

```
AccessGrant                      ← required argument; no overload without one
   │  verify: signature · expiry · nonce unused · category matches declaration
   ▼
resolve inputs                   (fixed binding context only)
   ▼
render request                   (protocol-aware escaping)
   ▼
transport                        (adapter · timeout · bulkhead keyed on DATA SOURCE)
   ▼
normalize → JsonNode             (XML→JSON, CSV row→JSON)
   ▼
detect application-level errors  (error_paths — HTTP 200 with a fault body is normal)
   ▼
data-quality validation          → failure → exception queue
   ▼
apply mapping DSL
   ▼
validate against output_schema
   ▼
attach _provenance               (source · as_of · connector@version · grant_id)
   ▼
audit + metrics  →  return JsonNode         ⟶ never persisted
```

**Resilience is keyed on `data_source`, not on connector.** The protected resource is the
department's server. Six connectors pointing at Revenue must share one circuit breaker and
one bulkhead; otherwise Revenue being slow exhausts the thread pool six times over and
takes Education's requests down with it.

### 7.4 Batch mode

```
02:00 poll → checksum → stream rows → per-row DQ
      → upsert registry pointers (as_of = file timestamp)
      → bad rows → exception queue
      → record file + row offset          (restartable)
```

```java
// ponytail: single-node chunked job with offset restart.
// Move to Spring Batch if files exceed ~1M rows or need partitioned parallel steps.
```

### 7.5 SUBMIT idempotency

A retried `FETCH` is free. A retried `SUBMIT` files the same application twice.

Authorization and operation are **different lifecycles** and are modelled separately:

```
submission_attempt
├─ id                      the idempotency key
├─ grant_id                the authorization that permitted it
├─ workflow_instance_id
├─ connector_id
├─ request_hash
├─ status                  PENDING | SUCCEEDED | FAILED
└─ external_reference      the department's own reference
```

For the MVP the submission key is derived from the grant, but the concepts remain
separate — nothing in the model assumes *one grant = one submission* permanently.

A retry after a timeout resolves to the original `external_reference` rather than creating
a duplicate. This is the single most likely real bug in the system and costs one table to
prevent.

### 7.6 Where AI is used, and where it is deliberately absent

**AI is used in exactly two places**, both advisory:

1. Suggesting field mappings during department onboarding (§8).
2. Scoring identity match candidates (§5.3).

Neither can write to the system without human approval. Both record their confidence. Both
are fully auditable. This is principle **P3** — *machines propose, humans dispose* — not
two coincidental design choices.

**AI is deliberately absent from the request path.** No language model sits between a
citizen and their income certificate. Government data access must be deterministic,
reproducible and explainable: the same request must produce the same result, and the system
must be able to state why.

### 7.7 Connector lifecycle

```
DRAFT ──test──► PUBLISHED@v1 ──► PUBLISHED@v2 ──► DEPRECATED ──► RETIRED
                     ▲                                 │
              journeys pin a major version ─────────────┘
```

A live connector is never edited. **Test** executes the real connector against a synthetic
subject and asserts the output validates against `output_schema`, displaying a
source→canonical diff. The same code path serves both the wizard's *Test connection* button
and the CI contract test.

### 7.8 Testing

| Layer | What it catches |
|---|---|
| Mapping DSL unit tests | Transform function regressions |
| Connector contract tests — golden fixtures replayed through WireMock | A department silently renaming a field. Runs in CI with no mocks running |
| One end-to-end test per journey against docker-compose | Integration regressions |

---

## 8. Department Onboarding

### 8.1 Phase 1 — the wizard

```
Register department → add data source → store credentials
   → define connector → map fields → test → publish
```

Six screens over catalog CRUD. Credentials are write-only in the UI: settable, never
readable.

### 8.2 Phase 2 — specification import

```
Upload OpenAPI / WSDL
     ↓  swagger-parser
list operations → select one
     ↓
extract response schema → flatten to field paths
     ↓
SUGGEST MAPPINGS
  ├─ lexical  : normalized edit distance + token match
  │             (dob · date_of_birth · dateOfBirth · janm_dinank)
  ├─ type     : compatibility check
  └─ semantic : optional model pass for non-obvious cases
     ↓
human reviews every suggestion, confidence displayed
     ↓
approved → mapping created → test → publish
```

This is Phase 4 work and is explicitly cuttable. The wizard alone still demonstrates
onboarding; the importer makes it compelling.

---

## 9. Security Architecture

### 9.1 Trust boundaries

```
  Browser  ──TLS──▶  Edge  ──▶  Core  ──▶  Department network
     │                │           │              │
 short-lived      rate limit  the only      untrusted:
 OIDC token      correlation  issuer of      may be down,
                 audit tap    grants         slow, or wrong
```

The fourth boundary is the one commonly forgotten. **Department responses are untrusted
input**: schema-validated, size-capped and data-quality checked before anything downstream
sees them. A department returning 400 MB or a malformed envelope must not affect platform
availability.

### 9.2 Three independent authorization axes

A request must clear all three. They answer different questions and are administered by
different people.

| Axis | Question | Mechanism |
|---|---|---|
| **Role** | Are you permitted to use this function? | Keycloak RBAC |
| **Purpose** | Did the citizen permit this use? | Consent artifact — purpose + category + validity |
| **Sensitivity** | Is your department cleared for this class? | Registry `sensitivity` vs requester clearance |

Roles: `CITIZEN`, `DEPT_OFFICER`, `IDENTITY_REVIEWER`, `INTEGRATION_ADMIN`, `AUDITOR`.

`AUDITOR` may read the entire audit chain and **no citizen data at all**. Oversight should
not require the power it oversees.

**Federated identity** is Keycloak identity brokering: the platform realm brokers to
per-department realms, so SSO crosses genuine organisational boundaries rather than sharing
one login.

### 9.3 Threat model

| Threat | Why it applies here | Mitigation |
|---|---|---|
| **SSRF via connector configuration** | A connector *is* an administrator-supplied URL the server fetches — SSRF as a feature | Base URLs only on `data_source`, set by `INTEGRATION_ADMIN`, **host-allowlisted**; link-local and private ranges blocked; connector definitions supply paths, never hosts |
| **XXE in SOAP/XML responses** | XML is parsed from systems we do not control | DTDs and external entities disabled in every parser; one `XMLInputFactory` configuration and a test asserting it |
| Stolen department credential | Held in a secret store, used constantly | Per-source credentials, rotation, `SecretStore` port, write-only UI; revocation affects one department |
| Insider officer browsing citizens | The classic government data breach | Purpose + sensitivity axes; every read audited, including discovery; access-rate anomalies surfaced on the dashboard |
| Replayed access grant | Grants cross a process boundary | 60-second TTL, single-use nonce, consent-version check |
| Tampered audit log | The integrity claim rests on it | §9.4 |
| SQL injection via JDBC adapter | We execute SQL against department databases | Bound parameters only, `SELECT`-only guard, read-only credential, row cap |

### 9.4 Tamper-evident audit

```java
byte[] canonical = CanonicalJson.serialize(entry);   // deterministic key ordering
entry.hash = sha256(concat(prevHash, canonical));
```

Written **in the same transaction as the business change** — a data access and its audit
record commit together or not at all. This is the specific reason `audit` lives inside the
monolith: as a remote service, every audited operation becomes a distributed transaction
whose failure mode is unaudited access.

A hash chain alone is insufficient. An attacker with write access to the table can rewrite
an entry and recompute every hash after it, and the chain will verify. Three measures close
this:

1. **Signed checkpoints.** Hourly, sign `{seq, root_hash, ts}` with a key held in the
   `SecretStore` — outside the database. Verification walks entries between two signed
   checkpoints. An attacker with full database access cannot repair the chain without the
   signing key.
2. **Append-only storage.** The application's database role holds no `UPDATE` or `DELETE`
   grant on `audit_entry`. This is why `audit` has its own schema and role — a functional
   requirement, not a stylistic one.
3. **External publication.** Checkpoints are published where the platform does not control
   them, providing an independent witness.

Deliberately **not** blockchain. A signed hash chain on append-only storage provides
equivalent tamper-evidence without consensus overhead.

The **verifier is a UI screen**, not a script: editing a row live and watching verification
fail makes the property concrete.

### 9.5 Data protection

- TLS in transit; mTLS to departments where supported.
- Manual uploads encrypted at rest, retention bound to application lifecycle.
- Secrets in `SecretStore`; never in `catalog_*`, never in configuration files.
- Strong identifiers tokenized, never stored raw.

---

## 10. Observability

Four dashboards, fed by Micrometer metrics and PostgreSQL queries. No additional
subsystem.

| Dashboard | Answers |
|---|---|
| **Connector health** | Which departments are reachable; p50/p95/p99 latency; breaker state; batch freshness lag |
| **SLA compliance** | Percentage of applications within SLA by journey and department; breaches with reasons |
| **Consent & access** | Grants issued vs denied, denial reasons, revocations, anomalous access patterns |
| **Exception queue** | Failed batch rows, unresolved fetch failures, identity candidates awaiting review |

Structured JSON logs carry the correlation ID from edge → workflow → connector → audit, so
a single citizen's application is traceable end to end.

Connector health is derived from breaker state, error rate and last-success time.

---

## 11. Frontend Architecture

**React 18 + TypeScript + Vite.** No SSR or SEO requirement, so Next.js adds build
complexity without benefit.

| Concern | Choice |
|---|---|
| Routing | React Router — role-scoped route groups |
| Server state | TanStack Query — caching, retry, invalidation |
| Runtime validation | Zod — API responses and forms |
| Forms | React Hook Form — connector configuration and field mapping are complex forms |

### 11.1 One application, three role-scoped surfaces

| Surface | Role | Capabilities |
|---|---|---|
| Citizen | `CITIZEN` | Link department IDs, view and revoke consents, unified tracking, notifications |
| Officer | `DEPT_OFFICER`, `IDENTITY_REVIEWER` | Application queue, authorized consolidated view, approvals, exception queue, identity review queue |
| Integration admin | `INTEGRATION_ADMIN`, `AUDITOR` | Department onboarding, connector builder, field mapping, connector health, audit explorer, SLA dashboards |

Three separate applications would mean three build pipelines and three auth integrations
for roughly forty screens.

### 11.2 Typed API client layer

The frontend must speak **domain concepts, not HTTP**.

```
src/
├── api/            client.ts · departments.ts · connectors.ts
│                   identity.ts · consent.ts · audit.ts · tracking.ts
├── features/       departments/ · connectors/ · identity/
│                   consent/ · audit/ · tracking/
├── components/     shared UI
├── routes/         role-scoped route definitions
├── types/          generated / Zod-inferred domain types
└── app/            providers, router, auth
```

```ts
const { data } = useConnector("rev-income");     // domain concept
// not:  fetch("/api/v1/catalog/connectors/123")
```

This matters increasingly as connector count grows: a URL change touches one file in
`api/`, not forty call sites.

---

## 12. Deployment

One `docker-compose.yml`, nine containers, runs on a laptop:

```
postgres              samanvay-core         samanvay-ui (nginx)
keycloak              mock-revenue     (SOAP + own Keycloak realm)
                      mock-education   (REST + static API key)
                      mock-municipal   (SFTP + nightly CSV)
                      mock-pollution   (PostgreSQL, JDBC only — no API)
                      mock-dbt         (REST + OAuth2)
```

**Everything runs offline.** Venue connectivity failure is a certainty, not a risk.
Deterministic seed data, no external calls, no CDN dependencies.

---

## 13. Journeys and Mock Departments

### 13.1 Mock departments are a deliverable

The heterogeneity is the proof. Four clean REST mocks would demonstrate nothing.

| Mock | Protocol | The specific pain it proves is handled |
|---|---|---|
| Revenue | SOAP / XML, own Keycloak realm | Legacy protocol + genuine identity brokering |
| Education Board | REST, static API key, poor field names | Auth diversity + schema normalization |
| Municipal | Nightly CSV over SFTP | **No realtime API at all** — batch ingestion, stale data as a first-class concept |
| Pollution Board | No API — read-only JDBC | The genuinely legacy case every government integration encounters |
| DBT | REST + OAuth2 | A modern, well-behaved counterexample |

Whoever writes an adapter also writes its mock — **mock first**, from a written
specification of how ugly it should be, then the adapter against it.

### 13.2 The three journeys

| Journey | Departments | Purpose |
|---|---|---|
| **1 — Post-matric scholarship** | Revenue (income, caste), Education (marks), DBT (bank) | Built concretely. Duplicate submission, consent, tracking, payout |
| **2 — Business licence / NOC** | Municipal (property), Fire, Pollution (JDBC), Revenue (land) | Forces generalization. Parallel fan-out, SLA, `Organisation` entity |
| **3 — Farmer subsidy** | Revenue (7/12 land record), Agriculture, DBT | **Configuration only.** `LandParcel` schema row, connector definitions, BPMN, journey policy |

Journey 3's acceptance criterion is verifiable: `git diff` for that work touches zero files
under `src/main/java`.

---

## 14. Build Plan

Five phases. The sequencing *is* the architecture argument — Phase 3 proves nothing unless
Phases 1 and 2 came first (P5).

| Phase | Delivers | Done when |
|---|---|---|
| **0 — Foundation** | Compose stack, Modulith skeleton, Flyway, Keycloak realms, **audit module**, CI running `ApplicationModules.verify()` + ArchUnit | A trivial endpoint writes a verifiable chained audit entry |
| **1 — Journey 1 concrete** | `catalog`, identity linking, `consent`, `registry`, access grant, REST + SOAP adapters, `WorkflowEngine` port, scholarship BPMN, `tracking`, thin UI | Scholarship runs end to end; no payload is ever persisted |
| **2 — Journey 2 + generalize** | Business NOC, SFTP + JDBC adapters, batch pipeline, stale policy, parallel fan-out, degraded mode, exception queue, identity resolution + review queue, onboarding wizard, `notifications` | Two journeys share one core; every `if (journey == …)` has been removed |
| **3 — Journey 3 as configuration** | Farmer subsidy: `LandParcel` schema, connector definitions, BPMN, journey policy | **`git diff` touches zero files under `src/main/java`.** Tag the commit |
| **4 — Extension + hardening** | OpenAPI import + mapping suggestions, dashboards, audit verifier UI, chaos drills, demo rehearsal | The demo runs three times without intervention |

**Audit lands in Phase 0 deliberately.** Retrofitting an audit trail means revisiting every
write path in the system.

### 14.1 Ownership

| Owner | Modules | Note |
|---|---|---|
| **Technical lead** | Access grant, `consent`, `audit` — the security spine | Critical path; reviews every PR crossing a module boundary |
| B | `identity` — linking, resolution, review queue — and `registry` | Hardest domain logic, most independent. Both modules answer "who and what exists" |
| C | `connector` runtime + REST & SOAP adapters + those two mocks | Critical path |
| D | `catalog` + onboarding wizard + SFTP & JDBC adapters + those two mocks | Owns the Phase 4 importer |
| E | `orchestration` (Flowable port, BPMN) + `tracking` + `notifications` | Owns the Flowable risk |
| F | All three UI surfaces | Unblocked once Phase 1 APIs stabilize; begins against mocked JSON |

**Critical path:** audit → access grant → connector runtime → Journey 1. Identity and
frontend proceed in parallel; neither blocks the demo spine.

---

## 15. Risks

| Risk | Mitigation | Fallback |
|---|---|---|
| Flowable learning curve | E owns it from week 1; the `WorkflowEngine` port already exists | YAML DAG executor behind the same port (~2 days) |
| Keycloak brokering configuration is fiddly | Timeboxed to 3 days in Phase 0 | Single realm; brokering presented as design |
| OpenAPI importer consumes the schedule | Phase 4, explicitly cuttable | Wizard alone still demonstrates onboarding |
| Six developers of uneven experience | Modulith + ArchUnit fail the build on boundary violations | — |
| Live demo failure | Deterministic seeds, fully offline, three rehearsals | Recorded backup video |
| Officer review queue volume (P3 invariant) | Deterministic matches auto-link; only genuinely ambiguous cases queue | Batch review UI with bulk confirm for high-confidence clusters |

---

## 16. Demo Script

Eight minutes. Written now because it disciplines the build — anything not serving these
beats is Phase 4 or later.

| # | Beat | Proves |
|---|---|---|
| 1 | Three portals, the same form filled three times | The problem |
| 2 | Citizen links department IDs; SSO across department IdPs | Identity linking, federated identity |
| 3 | Scholarship application — live parallel fan-out to three departments | Orchestration, connectors, consent |
| 4 | **Revenue killed mid-flight.** Application continues; officer sees the exception; retry recovers | Degraded mode, resilience |
| 5 | Citizen revokes consent → next access denied within 60 seconds | *"Nothing to purge. It was never stored."* |
| 6 | Audit explorer: verify the chain, edit a row, watch verification fail | Tamper-evidence |
| 7 | **Onboard a new department live** — upload specification, approve suggested mappings, test, publish | Plug-and-play extensibility |
| 8 | **Run Journey 3 using only the configuration from beat 7.** Show `git diff`: no Java | *"We did not build a scholarship system."* |

Beat 8 is the close, and it is available only because of the concrete→generalize→prove
sequencing (P5).

---

## 17. Glossary

| Term | Meaning |
|---|---|
| **Access Grant** | Short-lived (60s), single-use, signed capability authorizing exactly one data access. Required argument to the connector runtime |
| **Canonical model** | JSON Schema-defined transport contract for exchanged data. Not a source of truth |
| **Connector** | Configuration describing *what* government resource is accessed |
| **Consent artifact** | Long-lived citizen authorization: requester, purpose, categories, validity, revocation |
| **Control plane** | Modules that decide: identity, consent, registry, catalog |
| **Data plane** | Modules that move: orchestration, connector, tracking, notifications |
| **Discovery** | Learning that data exists, without accessing it. An authorized, audited operation |
| **Identity Linking** | The citizen proves two departmental IDs are theirs |
| **Identity Resolution** | The system proposes that records may belong to one person |
| **Journey** | A configured citizen service flow (BPMN + policy + connector references) |
| **Pointer** | Registry row recording that data exists at a department, with validity and freshness |
| **Protocol adapter** | Implementation describing *how* to communicate — REST, SOAP, SFTP, JDBC |
| **Provenance** | Origin metadata attached to every canonical value: source, `as_of`, connector version, grant |

---

*End of High Level Design.*
