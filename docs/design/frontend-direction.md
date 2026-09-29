# Design direction — Samanvay government operational layer

_2026-09-29 · the short design direction doc the redesign brief asks for (deliverable #1)._

## Subject, pinned

A **state-government operational console + citizen service**. Two audiences, one product:

- **Citizens** — often on a phone, low bandwidth, non-technical, anxious about paperwork. They need
  calm, generous, plain-language screens with one obvious next action.
- **Clerks / officers / operators** — desktop, sometimes low-end screens, working a queue all day.
  They need dense, quiet worklists and forms where status reads at a glance and nothing shouts.

It is technical underneath, but the surface is named by what a person **does** ("Review
applications", "Connect your records", "Approve", "Retry"), never by how the system is built.

Identity: **Government of Maharashtra (demo)** — it should feel like a real, official government
service, but it is a prototype and says so. No real seals, logos or domains presented as genuine.

## Tokens

### Palette (calm, official — not a dark "tech" theme, not the cream-serif-terracotta AI default)

A cool, neutral-paper government palette anchored by a deep official blue, with a single restrained
saffron used only as the **signature identity mark** (a thin top stripe and the "demo" tag), never as
a button or a large fill. Six named roles, each meeting ≥ 4.5:1 for text on its background in both
schemes:

| Role | Light | Dark | Use |
|---|---|---|---|
| `--brand` | `#0d4a7a` deep official blue | `#7fb2e6` | primary actions, active nav, links |
| `--brand-strong` | `#0a3960` | `#a8ccf0` | pressed / emphasis |
| `--seal` | `#c25e00` restrained saffron | `#e8a24d` | identity stripe + "demo" tag ONLY (signature) |
| `--ink` | `#14202b` | `#eef2f6` | body text |
| `--muted` | `#4c5a68` | `#aeb9c6` | secondary text, labels |
| `--surface` | `#f4f6f9` cool paper | `#0f151b` | page background |
| `--paper` | `#ffffff` | `#182029` | cards, header, inputs |
| `--line` | `#d5dce4` | `#31404f` | borders, dividers |

Status stays a separate, consistent triad (with washes): `--ok` green, `--warn` amber, `--bad` red,
plus `--info` blue. The **same tokens drive citizen and staff**, so a "needs action" amber means the
same thing everywhere.

### Type

System UI stack (`system-ui … 'Nirmala UI' …`) — deliberately, see the risk below. What makes it read
as official is the **scale and rhythm**, not a novelty face:

- An **eyebrow** style (uppercase, tracked, `--muted`) labels every section and the department name.
- A tight heading scale (page 1.9rem / section 1.2rem / card 1.05rem), generous body line-height,
  comfortable at small sizes and in long tables.
- Tabular numbers for reference numbers, counts and dates so worklists align.

### Layout

- **Citizen**: single readable column (≤ 62rem), generous vertical rhythm, big touch targets, one
  primary action per screen.
- **Staff**: wide container (≤ 82rem), dense tables and stat tiles, a quiet card grid on the home.
- Shared: a sticky **official header** with the demo identity, a persistent skip link, an 8px spacing
  scale, an 8px radius.

## Signature element

**One shared status language + a thin tricolour-inspired identity stripe.**

- Every surface uses the same **status pill** and the same **stepper** vocabulary and colours, so a
  citizen's "In progress" and an officer's worklist row speak the same visual language. This is the
  thread that makes the two surfaces read as one product.
- A 3px `--seal` → `--brand` stripe sits under the header on every page — a restrained nod to an
  official masthead that ties citizen and staff together without impersonating a real emblem.

## The one risk I took, and why

**Staying on the system-font stack instead of loading a bespoke display/serif pairing.** A custom
government-feeling typeface would add polish, but: (1) the app is served as static files by Spring
with no font pipeline and a locked-down origin, so a web-font fetch adds a network/CSP dependency and
a FOUT on exactly the low-bandwidth citizen devices we care about; (2) `system-ui` already includes
`Nirmala UI` / `Noto`, which matters for the Marathi copy the static portals had and this app should
regain. So the officiality comes from **colour, scale, spacing and the signature stripe/status
language**, not a downloaded face. If a font pipeline lands later, only the two `--font*` tokens
change — nothing else in the system does.

## Non-negotiable quality floor (carried into every slice)

Responsive to mobile · visible keyboard focus · semantic landmarks · WCAG AA contrast ·
`prefers-reduced-motion` respected · real form labels + error text · active-voice, sentence-case,
outcome-named copy · motion subtle and purposeful.
