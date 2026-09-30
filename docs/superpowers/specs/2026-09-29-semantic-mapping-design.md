# Semantic mapping pass for connector onboarding (propose-only)

_Design spec — 2026-09-29 · Roadmap workstream H_

## Goal

Make the onboarding **mapping suggestions** understand field *meaning*, not just spelling, so an
operator importing a department's OpenAPI spec gets correct proposals even when the source field name
is an abbreviation or domain synonym of the canonical target (e.g. `dob` → `dateOfBirth`,
`acct_no` → `accountNumber`, `amt` → `amount`, `mobile` → `phone`). Today `MappingSuggestor` is
**lexical only** (normalise + substring + Levenshtein), so semantically-equal-but-spelled-differently
fields score below threshold and are never proposed. This adds a **semantic pass** on top of the
lexical one. It stays **propose-only**: suggestions are advisory (`approved=false`), nothing in the
catalog is mutated, and a person still ticks each row before it becomes a rule.

## Background — current state

- `MappingSuggestor.suggest(sources, targets)` returns, per target, the best source above a 0.45
  confidence, tagged `rationale="lexical"`, `approved=false`.
- Used only by `SpecImportService.preview()` → `ImportPreview` (the OpenAPI-import step of the
  admin onboarding wizard). The importer **only previews**; publishing needs a person's ticks.
- `MappingSuggestion(source, target, confidence, rationale, approved)` already carries a `rationale`
  and an `approved` flag, so the propose-only contract is in place — we only improve the matching and
  label semantic matches distinctly.

## Approach (chosen): curated concept dictionary, deterministic, no external infra

Add a small **curated concept dictionary** in the catalog internal service: a map from a normalised
field alias to a canonical concept (e.g. `dob`, `dateofbirth`, `birthdate` → `DATE_OF_BIRTH`). A
source and a target that resolve to the **same concept** are a strong semantic match. For each target
the suggestor now takes the **higher** of the lexical and semantic score; a semantic win is tagged
`rationale="semantic"`, a lexical win stays `rationale="lexical"`. Threshold, ordering and the
propose-only flags are unchanged.

Deterministic and dependency-free — it fits HLD §1.5 "out of scope: no ML/LLM services, no external
infra". The dictionary is domain-focused (identity, finance/DBT, address, education) and intentionally
small; it is package-private and easy to extend as new departments onboard.

Alternatives rejected:
- **Embeddings / an LLM matcher** — needs an external model/service; out of scope, non-deterministic,
  and overkill for a propose-only hint.
- **Token-level synonym expansion** — risks over-matching generic tokens (`no`, `date`, `id`); the
  whole-field alias→concept lookup is precise and auditable.

## Components to build

1. A concept dictionary (normalised alias → concept) in `catalog.internal.service`, package-private.
2. A `semanticScore(source, target)` in `MappingSuggestor`: `0.9` when both resolve to the same
   concept, else `0`. Combine with the existing lexical score by `max`, label the rationale by which
   won, keep `approved=false` and the 0.45 threshold and confidence-desc ordering.

## Data flow (unchanged shape)

OpenAPI import → `SpecImportService.preview(sources, targets)` → `MappingSuggestor.suggest(...)`
(now lexical **+** semantic) → `ImportPreview.suggestions` → operator ticks rows → mappings saved.
Nothing is auto-applied.

## Testing

- `MappingSuggestorTest`: keep the lexical cases; add semantic cases (`dob`→`dateOfBirth`,
  `acct_no`→`accountNumber`, `mobile`→`phone`) asserting they now match with `rationale="semantic"`
  and `approved=false`; assert an unrelated field (`favourite_colour`) is still not proposed; assert a
  lexically-identical pair keeps `rationale="lexical"` (lexical wins ties).
- `./mvnw -o -Dtest=MappingSuggestorTest,MappingServiceTest test` green; full `verify` in CI.

## Non-goals / consequences

- Not an ML/embedding matcher; a curated dictionary only.
- Still propose-only — no catalog mutation, no auto-approve.
- No API/schema/migration change; `MappingSuggestion`'s shape is unchanged.

## Acceptance criteria

1. Semantically-equal fields that lexical scoring misses are now proposed, tagged `semantic`.
2. Lexical behaviour and the propose-only contract (`approved=false`, no mutation) are unchanged.
3. Threshold, ordering and the `ImportPreview` shape are unchanged; unrelated fields stay unproposed.
4. Targeted catalog tests pass locally; the module stays within its package (ArchUnit/modulith green).
