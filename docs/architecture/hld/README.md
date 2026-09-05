# Module HLDs

One document per module. Each is the **charter** handed to that module's owner: what it is
responsible for, what it must never do, what it exposes, what it owns, and what "done"
means.

Cross-cutting context lives in the [combined HLD](../HLD.md) and is referenced by section
rather than repeated. **The combined HLD is the single source of truth for principles,
flows and technology decisions.** If a module document contradicts it, the combined HLD
wins and the module document is wrong.

## Index

| # | Module | Plane | Owner | Phase |
|---|---|---|---|---|
| [01](01-audit.md) | `audit` | Cross-cutting | Lead | 0 |
| [02](02-catalog.md) | `catalog` | Control | D | 1 |
| [03](03-identity.md) | `identity` | Control | B | 1 → 2 |
| [04](04-registry.md) | `registry` | Control | B | 1 → 2 |
| [05](05-consent.md) | `consent` + `AccessAuthority` | Control | Lead | 1 |
| [06](06-connector.md) | `connector` | Data | C (REST/SOAP) · D (SFTP/JDBC) | 1 → 2 |
| [07](07-orchestration.md) | `orchestration` | Data | E | 1 → 2 |
| [08](08-tracking.md) | `tracking` | Data | E | 1 |
| [09](09-notifications.md) | `notifications` | Data | E | 2 |

## Dependency graph

Acyclic by construction. Arrows point from dependant to dependency.

```
                        ┌─────────┐
       everything ─────►│  audit  │◄──── SecretStore (infra)
                        └─────────┘

                        ┌─────────┐
        ┌──────────────►│ catalog │◄──── SecretStore
        │       ┌───────└─────────┘◄──────┐
        │       │            ▲            │
   ┌────┴───┐ ┌─┴────────┐   │      ┌─────┴─────┐
   │identity│ │ registry │   │      │ connector │
   └────┬───┘ └─┬────────┘   │      └─────┬─────┘
        │       │            │            │
        └───┬───┘            │            │
            ▼                │            │
       ┌─────────┐           │            │
       │ consent │───────────┘            │
       │  + AccessAuthority │◄────────────┘
       └────┬────┘
            │
            ▼
     ┌──────────────┐
     │ orchestration│──────► connector, identity (submitCandidate)
     └──────┬───────┘
            │  events only
            ▼
   ┌──────────┐   ┌───────────────┐
   │ tracking │   │ notifications │
   └──────────┘   └───────────────┘
```

### Three dependency rules that keep it acyclic

**1. `tracking` and `notifications` are event consumers only.** `orchestration` never calls
them. It publishes; they listen. A direct call from `orchestration` to `tracking` would be
the first cycle, since `tracking` needs orchestration's event types.

**2. `identity` never calls `connector`.** Identity resolution needs department records to
score candidates — but `connector` depends on `consent`, and `consent` depends on
`identity`, so `identity → connector` would close a cycle.

Instead, `orchestration` drives resolution scans: it fetches records through `connector`
under an admin-purpose grant and calls `identity.submitCandidate(...)`. This is also
correct under **P2** — moving data is the data plane's job, not the control plane's.

**3. Every module exposes exactly one `api` package.** Everything else is internal and
invisible to other modules. Spring Modulith enforces this at build time via a named
interface:

```
com.samanvay.<module>.api        ← public: interfaces, DTOs, events
com.samanvay.<module>.internal   ← private: entities, repositories, services
```

`ApplicationModules.of(SamanvayApplication.class).verify()` fails the build on any
violation.

## Module document template

Every document follows the same nine sections, so an owner always knows where to look:

1. Purpose · 2. Responsibilities (owns / explicitly not) · 3. Public interface ·
4. Data owned · 5. Events · 6. Key decisions · 7. Failure modes ·
8. Acceptance criteria · 9. Open questions for LLD

Section 9 is where unresolved detail is parked. It should be empty by the time the
corresponding LLD is written.
