# SIH judge script (eight minutes)

Boot with the demonstration profile so officer recovery and audit tamper work:

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

Open `http://localhost:8080/`. Do **not** open Caller, Command, or Ops first.

| Min | Where | What you say |
|---|---|---|
| 0:00 | `/` Citizen services | Two independent government portals. Same citizen would otherwise fill the same facts twice. Samanvay is not on this screen. |
| 0:40 | `/scholarship/` | Scholarship is a Directorate of Higher Education service. Connect Revenue, Education, DBT. Consent in plain language. Submit. |
| 2:30 | `/scholarship/#officer` | Officer desk. Login `officer` / `demo-2026` (labelled demonstration, not SSO). Open the file. Mark Revenue records unavailable, restore, Retry. Everyday words only. |
| 4:30 | `/licence/` | Second government service: Directorate of Industries. Municipal, Fire, Pollution, Revenue. Same middle layer, different portal. Submit or show the apply path. |
| 6:00 | `/audit.html` | Optional cutaway: verify the chain, tamper, fail. Data was never stored in Samanvay. |
| 7:00 | `/schemes.html` | Farmer subsidy is catalog configuration — no new Java. Proof the platform is generic. |
| 8:00 | Stop | Close on: we did not build a scholarship product. We built interoperability. |

Staff tools (External caller, Telemetry, Onboard) stay off-camera unless a judge asks.

Full technical rehearsal: [PHASE4_RUNBOOK.md](PHASE4_RUNBOOK.md).
