# Frontend redesign — one coherent government operational layer

_Design spec — 2026-09-29_

## Goal

Rebuild the Samanvay **frontend** so the citizen surface and the staff surfaces read as **one
coherent, calm, official government product** that a non-technical clerk or citizen can use without
training — consuming the existing backend APIs unchanged. Follows the redesign brief in
[`docs/handoff/frontend-redesign.md`](../../handoff/frontend-redesign.md) and the design direction in
[`docs/design/frontend-direction.md`](../../design/frontend-direction.md).

## Background — current state

The React SPA (`frontend/`, Vite + React 19 + TS strict, 184 passing Vitest tests) already covers the
full contract: citizen apply→connect→consent→submit→track, and the officer / ops / admin / reviewer
consoles. Styling is a single plain-CSS design-token file (`src/index.css`, ~634 lines) with a
cream-wash / navy palette. It works and is accessible, but:

- the palette drifts toward the generic cream default the brief calls out;
- there is **no single identity** tying the two surfaces together;
- status wording/colour is close but not a deliberate shared system;
- there is no real **home/dashboard** framing for staff beyond a card list.

The separate static "Phase-UI" HTML pages under `src/main/resources/static/` are out of scope here
(retiring them is a later slice); this spec unifies on the React SPA.

## Approach (chosen): design-system-first, DOM-safe slices

Rebuild the **look and system** first, in a way that restyles every existing surface at once and keeps
all 184 tests green, then layer richer structure surface by surface. Chosen because the DOM is already
semantic and well-tested: the fastest route to "one coherent product" is a token + component-CSS
overhaul that every page inherits, with **no DOM/text churn** in slice 1 (so nothing regresses), then
targeted JSX enhancements.

Alternatives rejected:
- **Full ground-up rewrite of every page** — throws away 184 green tests and a correct API layer for
  no contract benefit; high regression risk, slow.
- **Tailwind / component-library swap** — large dependency and build change for a prototype whose
  plain-CSS system is already small and controllable.

## Components / slices

### Slice 1 — Design system (this change)
Rewrite `src/index.css` to the design direction: the eight-role cool-paper + official-blue palette,
the restrained `--seal` saffron used only for the signature stripe and the demo tag, the type scale
(eyebrow, tabular numerals), the 8px spacing scale, and the **shared status language** (`.badge`,
`.steps`, tiles) unified across citizen and staff. Add the identity stripe via `::before` on the
header (no markup change). **Every existing class name is preserved**, so all current pages restyle
with zero DOM or copy changes and every test stays green.

### Slice 2 — Shared shell + identity (later)
`CitizenLayout` / `StaffLayout`: an official masthead ("Government of Maharashtra · demo"), a clear
citizen⇄staff relationship, a consistent footer. Keep the `Citizen services` brand text the App test
pins.

### Slice 3 — Staff home as an operational dashboard (later)
Turn `StaffHomePage` from a card list into a real landing: "what needs attention now" (open
exceptions, bank reviews, SLA breaches) as stat tiles linking into the queues, then the console cards.

### Slice 4 — Citizen flow polish (later)
Landing → services → apply (connect→consent→submit) → track, using the shared stepper and status
pills end to end; strengthen empty states and error copy.

## Non-goals

- No backend API, schema or migration changes (frontend-only, per the brief).
- No retiring of the static portals in this change.
- No new runtime dependency (stay on plain CSS + the current stack).
- No web-font pipeline (see the direction doc's risk note); tokens leave room for one later.

## Acceptance criteria (slice 1)

1. `src/index.css` implements the direction's palette, type scale, spacing and shared status language,
   in light **and** dark, all text ≥ WCAG AA on its background.
2. The signature identity stripe appears under the header on every page, with no markup change.
3. `npm run build`, `npm run lint`, `npm test` all pass unchanged (184 tests green); no DOM/copy
   changes, so no test edits are needed.
4. `prefers-reduced-motion` and visible keyboard focus are preserved.
5. The design direction doc and this spec are committed under `docs/`.
