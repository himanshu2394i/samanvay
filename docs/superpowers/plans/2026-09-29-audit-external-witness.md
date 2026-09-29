# Implementation plan — audit external witness

_Plan for [`../specs/2026-09-29-audit-external-witness-design.md`](../specs/2026-09-29-audit-external-witness-design.md) · 2026-09-29 · Roadmap H_

TDD, inside `com.samanvay.audit`. Same branch/PR as the semantic-mapping pass (branch constraint keeps
one branch); both are Workstream H buildable items.

1. **Baseline** — audit unit tests green (`ChainCheckpointSchedulerTest`, `AuditKeyRotationTest`,
   `HashChainServiceTest`).
2. **Tests first**:
   - `FileCheckpointWitnessTest` — disabled without a dir; enabled → `sha256:` ref, append-only log,
     deterministic digest.
   - `ChainCheckpointSchedulerTest` — witness ref is stored on the checkpoint.
   - Run; watch fail for the right reason.
3. **Implement**:
   - `CheckpointWitness` port + `disabled()`;
   - `FileCheckpointWitness` `@Component` (config-gated by `samanvay.audit.witness.dir`, content-digest
     ref, fail-soft);
   - `CheckpointRepository.insert(..., publishedRef)` overload (INSERT-only privilege respected);
   - wire the scheduler to publish before insert; keep legacy constructors defaulting to `disabled()`.
4. **Verify** — audit unit tests + `ArchitectureTest` green; full `verify` (incl. `CheckpointKeyIdIT`,
   Docker/Postgres) in CI.
5. **Docs + commit + push (updates the open PR) + keep it watched.**

## Verification
```bash
./mvnw -o -Dtest='ChainCheckpointSchedulerTest,FileCheckpointWitnessTest,AuditKeyRotationTest,HashChainServiceTest,ArchitectureTest' test
```

## Risk / rollback
Additive and disabled by default; rollback is reverting the audit files. No migration, no privilege
change. The only runtime effect appears when a witness dir is configured, and even then publication is
best-effort and cannot block a checkpoint.
