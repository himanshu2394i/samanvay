# Samanvay — one page for judges

**SIH26129** · Government of Maharashtra · Interoperability among government digital platforms.

## The problem

Citizens fill the same facts on every department portal. Departments keep their own systems, protocols, and identity stores. A new citizen portal that copies all of that data is the wrong product.

## What we built

**Samanvay is the middle layer.** It does not replace Aaple Sarkar. It does not store department payloads. It owns only interoperability state: identity links, consent, catalog, workflow, tracking, audit.

Two **independent government services** call it:

| Service | URL | Departments |
|---|---|---|
| Scholarship Portal | `/scholarship/` | Revenue, Education, DBT |
| Business licence / NOC | `/licence/` | Municipal, Fire, Pollution, Revenue |

Farmer subsidy is a third journey added as **catalog configuration** (`/schemes.html`) — not a new Java application.

## What to click

1. `http://localhost:8080/` — citizen services directory  
2. Scholarship apply → Officer desk (`officer` / `demo-2026`) → Retry after Revenue unavailable  
3. Licence portal — second caller, same APIs  
4. `/audit.html` then `/schemes.html`

Demo identity is **AUTH STUBBED** (not live Keycloak). DigiLocker and OTP on the portals are labelled mocks.

## Principles (spoken)

Federated by default. Control plane decides, data plane moves. Machines propose, humans dispose, the decision is audited.

Spoken walkthrough: [JUDGE_SCRIPT.md](JUDGE_SCRIPT.md).
