# Implementation plan — semantic mapping pass

_Plan for [`../specs/2026-09-29-semantic-mapping-design.md`](../specs/2026-09-29-semantic-mapping-design.md) · 2026-09-29 · Roadmap H_

TDD, kept inside `com.samanvay.catalog.internal.service`. Branch: `claude/busy-galileo-x22v5f`
(reset onto `origin/main` after #60 merged).

1. **Baseline** — run `./mvnw -o -Dtest=MappingSuggestorTest,MappingServiceTest test`; confirm green.
2. **Tests first** (`MappingSuggestorTest`):
   - keep the two lexical cases (adjust the vacuous `dob` assertion, which now genuinely matches);
   - add: `dob`→`dateOfBirth`, `acct_no`→`accountNumber`, `mobile`→`phone` match with
     `rationale="semantic"`, `approved=false`, confidence high;
   - add: a lexically-identical pair keeps `rationale="lexical"` (lexical wins ties);
   - add: an unrelated field (`favourite_colour`) is not proposed.
   - Run; watch them fail for the right reason.
3. **Implement**:
   - add a package-private concept dictionary (normalised alias → concept) covering identity,
     finance/DBT, address and education fields;
   - add `semanticScore(source, target)` (0.9 same-concept else 0) to `MappingSuggestor`; per target
     take `max(lexical, semantic)`, tag rationale by the winner, keep `approved=false`, the 0.45
     threshold and confidence-desc ordering.
4. **Verify** — targeted catalog tests green; then a broader `./mvnw -o test` for the catalog module
   and the modulith/ArchUnit checks; full `verify` runs in CI.
5. **Docs + commit + push + draft PR + subscribe.**

## Verification
```bash
./mvnw -o -Dtest=MappingSuggestorTest,MappingServiceTest test
./mvnw -o -Dtest='com.samanvay.catalog.**' test   # catalog module
```

## Risk / rollback
Self-contained in one class + its test plus a dictionary; rollback is reverting the class. The only
cross-cutting risk is over-matching from a too-broad dictionary — mitigated by whole-field
alias→concept lookup (no generic-token expansion) and the unrelated-field test.
