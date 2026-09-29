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

## Slice 2 — Shared shell + identity (follow-up)
`CitizenLayout` / `StaffLayout`: official masthead with the demo identity, citizen⇄staff link, footer.
Keep the `Citizen services` brand text (App.test pins it). Update/extend tests for any new landmarks.

## Slice 3 — Staff operational dashboard (follow-up)
`StaffHomePage`: "needs attention now" stat tiles (open exceptions, bank reviews, SLA) linking into
queues, above the console cards. New tests for the tiles + role gating.

## Slice 4 — Citizen flow polish (follow-up)
End-to-end shared stepper + status pills across landing→services→apply→track; stronger empty/error
states; optional Marathi copy toggle. Tests per page.

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
