# LLD 10 — Name matching (shared)

One deterministic name matcher, `com.samanvay.shared.NameMatcher`, compares a
name we hold against a name a source returns (a bank holder name today; ULI /
land records later). It lives in `shared` so live adapters and the identity
module can reuse it without depending on each other.

## Where the holder's name goes

The source's holder name is passed to `match(...)` and thrown away immediately.
It is never logged, stored, put in an audit row, or included in an error
message. Only the `NameMatchResult` and the matcher `VERSION` are kept. #31's
canary leak test covers this on the success, timeout and malformed paths.

## Rules (Principal Architect's ruling on #31)

1. **Normalise first**: case, accents, punctuation, extra spaces, honorifics,
   and the order of the name parts. Honorifics come from
   `samanvay.name-match.honorifics` (defaults in `NameMatcher.DEFAULT_HONORIFICS`,
   Latin and Devanagari).
2. An **initial** counts only against a full part starting with the same letter.
3. All parts agree, no initial relied on → **MATCH**.
4. Two or more parts agree (or an initial carried an otherwise-full match) →
   **PARTIAL**.
5. Otherwise → **NO_MATCH**.
6. Different scripts (e.g. Devanagari vs Latin), or nothing to compare →
   **NOT_CHECKED**.

`PARTIAL`, `NO_MATCH` and `NOT_CHECKED` are hints for an officer; none of them
rejects on its own. `VERSION` (`name-match-v1`) is recorded with each decision so
a rule change is a version change, not a silent verdict change.

## Two decisions taken here (open in the ruling — Product to confirm)

Both err towards a human review rather than a silent MATCH:

- **An initial never yields MATCH.** A match relying on any initial is PARTIAL at
  most, so `R. Patil` vs `Rohan Patil` is PARTIAL, not MATCH (QA's sibling
  shared-account concern). If Product wants `R. K. Patil` vs `Rahul Kumar Patil`
  to be a full MATCH, this is the knob to change.
- **"Surname + one other" is read as "two or more aligned parts."** Order is
  normalised away, so the matcher cannot single out the surname. `Rahul Patil`
  vs `Rahul Kumar Patil` (missing middle) is PARTIAL; `Priya Deshmukh` vs
  `Pooja Deshmukh` (surname only) is NO_MATCH.

## Not here (follow-up)

Wiring into the bank-check adapter and the officer card, and transliteration
between scripts, are later PRs. This PR is the matcher and its tests only.
