# Stronger audit external witness (§9.4, propose/best-effort)

_Design spec — 2026-09-29 · Roadmap workstream H_

## Goal

Give the tamper-evident audit ledger a real **external witness** (HLD §9.4): publish each signed
checkpoint to an append-only sink outside the application database and record a reference to it on the
checkpoint row (`audit_checkpoint.published_ref`). Today checkpoints are created and signed hourly, but
nothing is published externally and `published_ref` is always null — so the one defence listed against
"collusion at the platform operator" (external checkpoint publication, HLD §9.4) is not actually
exercised.

## Background — current state

- `ChainCheckpointScheduler.createCheckpoint()` (hourly) reads the head hash, signs
  `root_hash || upto_entry_seq` with the active key, and `CheckpointRepository.insert(...)`s the row.
- `audit_checkpoint.published_ref VARCHAR(500)` already exists and is mapped into the `Checkpoint`
  record, but no code writes it.
- The app DB role has `SELECT, INSERT` (not `UPDATE`) on `audit_checkpoint` (LLD §privileges), so the
  reference must be written **in the INSERT**, not by a later UPDATE.

## Approach (chosen): publish-before-insert, pluggable witness, disabled by default

Add a `CheckpointWitness` port. The scheduler publishes the signed checkpoint to it **before** the
insert and stores the returned reference in the same INSERT (respecting the INSERT-only privilege). The
default `FileCheckpointWitness` appends a deterministic, single-line attestation to
`<dir>/checkpoints.log` in a configured directory (in the demo, a second container's mounted
filesystem — the HLD §9.4 open question) and returns a content digest (`sha256:…`) as the reference.

Best-effort by contract: witnessing is **disabled unless `samanvay.audit.witness.dir` is set** (so dev
and CI behave exactly as before — `published_ref` stays null), and a publication failure is logged and
swallowed so the signed checkpoint is still recorded. The signed hash chain remains the primary
tamper-evidence; the witness strengthens it.

Alternatives rejected:
- **UPDATE published_ref after insert** — the app role has no UPDATE on `audit_checkpoint`; would need
  a grant migration for no benefit.
- **A network witness (S3/transparency log)** — needs external infra/creds; out of scope (HLD §1.5).
  The port makes that a drop-in later; the file sink proves the mechanism now.

## Components to build

1. `CheckpointWitness` port (package-private, `audit.internal.service`) with a `disabled()` no-op.
2. `FileCheckpointWitness` (`@Component`) — file-backed, config-gated, content-digest reference,
   fail-soft.
3. `CheckpointRepository.insert(..., publishedRef)` overload writing `published_ref` in the INSERT; the
   old 4-arg delegates with null.
4. `ChainCheckpointScheduler` — publish before insert, store the reference; witness injected, with the
   existing constructors defaulting to `disabled()`.

## Testing

- `FileCheckpointWitnessTest`: disabled when no dir; enabled → returns a `sha256:` ref, appends one
  line per checkpoint, digest is deterministic (content-addressed) across directories.
- `ChainCheckpointSchedulerTest`: a witness returning a fixed ref → that ref is stored on the
  checkpoint; existing signature-verify and empty-chain cases still pass.
- No migration (column exists); ArchitectureTest keeps module boundaries. Full `verify`/ITs in CI.

## Non-goals / consequences

- Not a networked/transparency-log witness; a file sink behind a port.
- No schema/migration change; no new DB privilege.
- Disabled by default; enabling is a single config property, so no behavioural change without a mount.

## Acceptance criteria

1. When `samanvay.audit.witness.dir` is set, each new checkpoint is appended to the witness log and its
   `published_ref` records a verifiable content digest.
2. When unset, behaviour is unchanged (`published_ref` null) and checkpoints are still created/signed.
3. A witness failure never prevents a checkpoint being recorded.
4. Audit unit tests + ArchitectureTest green locally; full suite in CI.
