# SIH judge script (eight minutes)

Boot with the demonstration profile so officer recovery and audit tamper work:

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

Open `http://localhost:8080/`. Do **not** open Caller, Command, or Ops first.

| Min | Where | What you say |
|---|---|---|
| 0:00 | `/` Citizen services | Three independent government portals. Same citizen would otherwise fill the same facts twice. Samanvay is not on this screen. |
| 0:40 | `/scholarship/` | Scholarship is a Directorate of Higher Education service. Connect Revenue, Education, DBT. Consent in plain language. Submit. |
| 2:20 | `/scholarship/#officer` | Officer desk. Login `officer` / `demo-2026` (labelled demonstration, not SSO). Open the file. Mark Revenue records unavailable, restore, Retry. Everyday words only. |
| 4:00 | `/licence/` | Second service: Directorate of Industries. Municipal, Fire, Pollution, Revenue. Same middle layer. |
| 5:20 | `/farmer/` | Third service: Department of Agriculture. The **journey** is catalog configuration (no new Java). This page is only a caller skin. |
| 6:20 | `/audit.html` | Optional cutaway: verify the chain, tamper, fail. Data was never stored in Samanvay. |
| 7:20 | `/schemes.html` | Catalog list: three journeys, one core. Close on: we did not build three products. We built interoperability. |
| 8:00 | Stop | |

Staff tools (External caller, Telemetry, Onboard) stay off-camera unless a judge asks.

Full technical rehearsal: [PHASE4_RUNBOOK.md](PHASE4_RUNBOOK.md).
