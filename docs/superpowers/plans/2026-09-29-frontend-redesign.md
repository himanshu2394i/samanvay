# Implementation plan — frontend redesign

_Plan for [`../specs/2026-09-29-frontend-redesign-design.md`](../specs/2026-09-29-frontend-redesign-design.md) · 2026-09-29_

Design-system-first, delivered in DOM-safe slices so CI (`frontend` job: build + lint + test) stays
green throughout. Branch: `claude/busy-galileo-x22v5f`.

## Slice 1 — Design system (in this change)

1. **Baseline** — confirm `npm run build` / `lint` / `test` green before touching anything. ✅ (184 tests)
2. **Rewrite `frontend/src/index.css`** to the design direction, preserving every existing selector so
   no page markup changes:
   - Redefine `:root` tokens: `--brand`, `--brand-strong`, `--seal`, `--ink`, `--muted`, `--surface`,
     `--paper`, `--line`, status triad + `--info`, spacing scale (`--sp-*`), radius, fonts. Map the
     legacy `--accent`/`--accent-ink`/`--wash`/`--focus` names to the new tokens so nothing dangling.
   - Dark-mode block for the same roles; verify ≥ AA contrast both schemes.
   - Type: eyebrow utility, heading scale, tabular numerals on numbers/tables.
   - Signature: `.site-header::before` identity stripe (`--seal`→`--brand`), the "demo" tag styling.
   - Refine shared components against the new tokens: `.btn` family, `.card`, `.badge`, `.steps`
     stepper, `.tile(s)`, `.notice`, tables, forms, nav — the shared **status language**.
3. **Verify** — `npm run build && npm run lint && npm test` (must stay 184 green, no test edits since
   DOM/copy unchanged). Manual: dark mode, keyboard focus, mobile width, reduced motion.
4. **Docs** — commit the design direction doc + spec + this plan.
5. **Commit, push, open draft PR**, subscribe to PR activity.

## Slice 2 — Shared shell + identity ✅ (done)
`CitizenLayout` / `StaffLayout`: an official "Government of Maharashtra · Demo build" masthead identity
strip on both surfaces (same `.identity` line + signature stripe), tying them together. Kept the
`Citizen services` brand text and the `Staff consoles` footer link the App test pins.

## Slice 3 — Staff operational dashboard ✅ (done)
`StaffHomePage` now opens with a **Needs attention now** strip (`StaffAttention.tsx`): live open
exceptions, SLA breached, due soon (from ops metrics) and bank reviews waiting (officer), each a tile
linking into the console that resolves it. Role-scoped (reviewers see none) and degrades to a quiet
line when figures can't load. Covered by `StaffAttention.test.tsx` (tiles + role gating); `App.test`
stubs fetch offline for its routing assertions.

## Slice 4 — Citizen flow polish ✅ (done)
Eyebrow section labels for consistency with staff; the empty **My applications** state is now an
inviting card with a clear CTA. The shared stepper + status pills already span landing→services→
apply→track via the slice-1 design system. (Marathi copy toggle remains a future nicety.)

## Verification commands
```bash
cd frontend
npm run build      # tsc -b + vite build
npm run lint       # eslint
npm test           # vitest run (184)
npm run dev        # manual walk-through against the demo backend
```

## Risks / rollback
Slice 1 is pure CSS: rollback is reverting one file. The only cross-cutting risk is a token rename
leaving a selector unstyled — mitigated by keeping every legacy token name aliased to a new value.
