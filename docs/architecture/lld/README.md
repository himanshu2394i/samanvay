# Module LLDs

One low-level design per module: entities and DDL, class-level design, sequence diagrams
for the module's own operations, error handling, events, and the test list. Filenames
match [`hld/`](../hld/README.md) — `lld/06-connector.md` is the detailed companion to
`hld/06-connector.md`, not a replacement for it.

**Read [`../LLD.md`](../LLD.md) first.** It holds everything that applies across modules —
package layout, DB conventions (schema-per-audit-only, no cross-module foreign keys,
column types), the migration version-range allocation, error handling shape, testing
conventions, and the sequence diagrams for flows that cross module boundaries. A module
document below references those sections rather than repeating them.

## Index

Written and should be read in critical-path order — each module's design depends on
decisions made in the one before it:

| # | Module | Migration range | Depends on |
|---|---|---|---|
| [01](01-audit.md) | `audit` | V1–V19 | — (built first) |
| [05](05-consent.md) | `consent` + `AccessAuthority` | V80–V99 | `audit`, `identity`, `registry`, `catalog` |
| [06](06-connector.md) | `connector` | V100–V119 | `consent`, `catalog`, `audit` |
| [07](07-orchestration.md) | `orchestration` | V120–V139 | `consent`, `connector`, `catalog`, `identity` |
| [02](02-catalog.md) | `catalog` | V20–V39 | `audit` |
| [03](03-identity.md) | `identity` | V40–V59 | `catalog`, `audit` |
| [04](04-registry.md) | `registry` | V60–V79 | `catalog`, `audit` |
| [08](08-tracking.md) | `tracking` | V140–V159 | `catalog`, `audit` (consumes events only) |
| [09](09-notifications.md) | `notifications` | V160–V179 | `audit` (consumes events only) |

Filed in HLD order (01–09) for easy cross-reference; built in the order above because that
is the order the [HLD build plan](../HLD.md#14-build-plan) actually needs them.

## What's in each document

1. **Migrations** — the module's Flyway files, full DDL
2. **Entities** — JPA `@Entity` classes (or plain JDBC row mappers, where noted)
3. **Repositories** — Spring Data interfaces, including any native queries
4. **Domain services** — the internal classes implementing the module's public `api`
   interface (already specified at the HLD level; here with exact method bodies' logic,
   not just signatures)
5. **Sequence diagrams** — this module's own operations. Cross-module flows are in
   [`../LLD.md` §7](../LLD.md#7-cross-module-sequence-diagrams), not repeated here
6. **Error handling** — this module's exception types and what triggers each
7. **Events** — exact record shape for each published event
8. **Tests** — concrete test class names and what each proves
