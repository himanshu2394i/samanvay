# LLD: `orchestration`

| | |
|---|---|
| **HLD charter** | [hld/07-orchestration.md](../hld/07-orchestration.md) |
| **Migration range** | V120–V139 |
| **Package** | `com.samanvay.orchestration` |
| **Depends on** | `consent.api`, `connector.api`, `catalog.api`, `identity.api` (`submitCandidate`) |

---

## 1. Resolving the open question: two retry layers, two different jobs, not one fighting the other

[hld/07-orchestration.md §9](../hld/07-orchestration.md#9-open-questions-for-lld) flagged
that Resilience4j retries (inside `connector`) and Flowable timer retries (here) could
multiply if left unspecified. They don't multiply once each is given a distinct job:

| Layer | Handles | Duration | Visible as |
|---|---|---|---|
| Resilience4j (`connector`, keyed on data source) | A single flaky call — one dropped packet, one slow response | Sub-10-seconds, 2–3 attempts, short backoff | Invisible — resolved inside one `ConnectorRuntime.execute()` call |
| Flowable boundary timer (here) | A department genuinely down for an extended window | Minutes to hours, escalating | `PENDING_SOURCE` in the workflow's persisted history, survives restart |

**The rule that keeps them from compounding:** `connector`'s Resilience4j retry budget is
deliberately small (see [lld/06-connector.md §4](06-connector.md#4-connectorruntime--the-full-pipeline)) —
a step invocation always resolves in well under a second-scale timeout, one way or the
other. It is *never* the connector's job to wait minutes; that's what this module's timer
loop is for, expressed declaratively in the process definition, not as a Java retry loop
(a Java-level sleep-and-retry here would block a Flowable job-executor thread for the
entire wait — the same mistake as the retry itself, one level up).

## 2. Migration: `V120__orchestration_init.sql`

```sql
CREATE TABLE orchestration_instance (
    id                          UUID PRIMARY KEY,
    journey_code                VARCHAR(60) NOT NULL,
    citizen_id                  UUID NOT NULL,                -- soft ref to identity_citizen
    process_instance_id         VARCHAR(100) NOT NULL UNIQUE, -- Flowable's own id - see §5
    pinned_connector_versions   JSONB NOT NULL DEFAULT '{}',  -- {"rev-income": 3, ...}
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestration_step_state (
    id                    UUID PRIMARY KEY,
    instance_id           UUID NOT NULL REFERENCES orchestration_instance(id),
    step_code             VARCHAR(60) NOT NULL,
    status                VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','IN_PROGRESS','COMPLETED','PENDING_SOURCE','AUTHORIZATION_WITHDRAWN','FAILED')),
    attempt_count         INT NOT NULL DEFAULT 0,
    last_failure_reason   VARCHAR(200),
    next_retry_at         TIMESTAMPTZ,
    UNIQUE (instance_id, step_code)
);
```

Flowable's own tables (`ACT_RU_*`, `ACT_HI_*`, ...) are created by its own schema
management, namespaced by its own prefix, in the same `public` schema. Per
[hld/07-orchestration.md §4](../hld/07-orchestration.md#4-data-owned): **no other module,
and no query in this module either, reads a Flowable table directly.** Everything this
module needs about a running instance goes through Flowable's `RuntimeService` API or is
mirrored into `orchestration_step_state` above.

## 3. The BPMN retry loop — the actual diagram to draw in the modeler

This is the pattern behind "PENDING_SOURCE, retry, eventually escalate," expressed the
way Flowable actually executes it — a loop through a timer, not a Java retry block:

```
  ┌──────────────┐
  │ Service Task │  FetchDataDelegate — one ConnectorRuntime.execute() call,
  │ "Fetch X"    │  sets stepOutcome + attemptCount, never sleeps
  └──────┬───────┘
         │
         ▼
   ◇ stepOutcome? ◇
    │           │              │
 COMPLETED   PENDING_SOURCE   AUTHORIZATION_WITHDRAWN / NOT_FOUND
    │           │                        │
    ▼           ▼                        ▼
 (continue)  ◇ attemptCount < 5? ◇   Exception Queue
              │              │
             yes             no
              │              │
              ▼              ▼
    ⏱ Timer (${nextDelay})  Exception Queue
              │              (manual upload offered - HLD §6.2)
              ▼
     loop back to "Fetch X"
     (attemptCount + 1 set by a
      script task before the loop)
```

`${nextDelay}` is a process variable computed before each timer — exponential, e.g.
`30s, 2m, 10m, 30m, 2h` — set by a small script task, not hardcoded in the BPMN XML, so
the backoff curve is a one-line change rather than a redeploy of the diagram shape.

**Why this survives a server restart:** Flowable persists the process instance's
position — sitting at the timer catch event — to its own tables. On restart, its
`AsyncExecutor` reads pending timers from the database and fires them exactly as if the
process had been running the whole time. This is the entire reason "retries are Flowable
timers, not cron" ([HLD §6.2](../HLD.md#62-journey-execution--parallel-fan-out-and-degraded-mode))
is a real property, not just a description.

## 4. `FetchDataDelegate`

```java
package com.samanvay.orchestration.internal.workflow;

@Component("fetchDataDelegate")
class FetchDataDelegate implements JavaDelegate {

    private final AccessAuthority accessAuthority;    // consent.api
    private final ConnectorRuntime connectorRuntime;  // connector.api

    @Override
    public void execute(DelegateExecution execution) {
        var req = buildAccessRequest(execution);
        var decision = accessAuthority.authorize(req);

        if (decision instanceof AccessDecision.Denied denied) {
            execution.setVariable("stepOutcome", denied.reason().name());
            if (denied.reason() == DenialReason.NO_CONSENT)
                throw new BpmnError("NO_CONSENT", denied.remedy().map(ConsentRequest::id).orElse(null));
            return;
        }

        var grant = ((AccessDecision.Granted) decision).grant();
        var result = connectorRuntime.execute(grant, Capability.FETCH, buildInputs(execution));

        switch (result) {
            case ConnectorResult.Success s -> {
                execution.setVariable(execution.getCurrentActivityId() + "_result", s.canonical());
                execution.setVariable("stepOutcome", "COMPLETED");
            }
            case ConnectorResult.Unavailable u when u.retryable() ->
                execution.setVariable("stepOutcome", "PENDING_SOURCE");   // gateway in §3 routes to the timer loop
            case ConnectorResult.NotFound nf -> execution.setVariable("stepOutcome", "NOT_FOUND");
            case ConnectorResult.Invalid inv -> execution.setVariable("stepOutcome", "INVALID");
            default -> execution.setVariable("stepOutcome", "FAILED");
        }
    }
}
```

**The invariant from HLD §6, made structural again:** there is no path in this class that
calls `connectorRuntime.execute(...)` without first obtaining a `Granted` decision from
`accessAuthority.authorize(...)`. A BPMN process definition could not bypass this even if
someone tried to wire a service task directly to a `connector` bean — `ConnectorRuntime`
is never exposed to the workflow layer except through delegates like this one that always
go through `AccessAuthority` first.

## 5. `WorkflowEngine` — the Flowable implementation behind the port

```java
package com.samanvay.orchestration.internal.workflow;

@Component
class FlowableWorkflowEngine implements WorkflowEngine {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;

    @Override
    public String deploy(String definitionRef, byte[] bpmnXml) {
        return repositoryService.createDeployment()
            .addBytes(definitionRef + ".bpmn20.xml", bpmnXml)
            .name(definitionRef).deploy().getId();
    }

    @Override
    public String start(String processKey, Map<String, Object> variables) {
        return runtimeService.startProcessInstanceByKey(processKey, variables).getId();
    }

    @Override
    public void signal(String instanceId, String signal, Map<String, Object> variables) {
        runtimeService.signalEventReceived(signal, instanceId, variables);
    }

    @Override
    public EngineState state(String instanceId) {
        var instance = runtimeService.createProcessInstanceQuery()
            .processInstanceId(instanceId).singleResult();
        return instance == null
            ? EngineState.completed()
            : EngineState.active(runtimeService.getActiveActivityIds(instanceId));
    }
}
```

`scheduleRetry(...)` is declared on the port (for the YAML-DAG-executor fallback, which
would need to schedule imperatively) but has no Flowable implementation — Flowable
expresses the retry as the timer catch event in §3's diagram, declaratively, not via an
imperative scheduling call. This asymmetry is fine: a port's methods don't all need to be
meaningful for every implementation, only callable.

## 6. Identity resolution scans — the inversion from HLD §7

`identity` cannot depend on `connector` (see
[hld/03-identity.md §6](../hld/03-identity.md#6-key-decisions) and
[hld/README.md's dependency rules](../hld/README.md#three-dependency-rules-that-keep-it-acyclic)).
This module owns the scan instead:

```java
package com.samanvay.orchestration.internal.service;

@Component
class IdentityResolutionScanner {

    private final AccessAuthority accessAuthority;   // admin-purpose grant, not a citizen-purpose one
    private final ConnectorRuntime connectorRuntime;
    private final IdentityResolution identityResolution;   // identity.api

    @Scheduled(cron = "0 0 3 * * *")
    void scan() {
        for (var departmentRecord : fetchCandidateRecordsUnderAdminGrant()) {
            identityResolution.submitCandidate(departmentRecord.departmentCode(), departmentRecord.payload());
        }
    }
}
```

`fetchCandidateRecordsUnderAdminGrant()` goes through the exact same
`AccessAuthority` → `ConnectorRuntime` path as any other fetch (§4), with
`purpose = ADMIN_DEDUP` — resolution has no special bypass of consent/authorization, it is
simply a different, audited purpose.

## 7. Error handling

| Exception (`orchestration.api`) | Raised when |
|---|---|
| `JourneyNotFoundException` | `start()` given an unknown `journeyCode` |
| `InstanceNotFoundException` | `signal()`/`cancel()`/`state()` given an unknown instance |

Denials and connector failures are **not** exceptions here — they're `stepOutcome` values
routed by the BPMN gateway in §3, exactly per
[LLD.md §4.3](../LLD.md#43-what-is-never-caught-and-swallowed): these are expected
outcomes the process must branch on, not error conditions to unwind from.

## 8. Events

| Event | Payload | Published when |
|---|---|---|
| `JourneyStarted` | `{instanceId, journeyCode, citizenId}` | `start()` completes |
| `StepCompleted` / `StepFailed` | `{instanceId, stepCode, outcome}` | Each `FetchDataDelegate` (and its `SUBMIT` equivalent) resolves |
| `StepPendingSource` | `{instanceId, stepCode, attemptCount, nextRetryAt}` | Gateway routes to the timer loop |
| `ApplicationStateChanged` | `{instanceId, newStatus}` | Aggregate status changes, incl. → `PARTIALLY_VERIFIED` |
| `SlaBreached` | `{instanceId, stepCode, dueAt}` | A step's SLA timer boundary event fires before completion |
| `ManualUploadRequested` | `{instanceId, stepCode, category}` | Retry budget exhausted, escalating to citizen upload |

## 9. Tests

| Test | Proves |
|---|---|
| `FetchDataDelegateTest` (unit, mocked `AccessAuthority`/`ConnectorRuntime`) | Every `ConnectorResult` variant maps to the correct `stepOutcome`; `Denied(NO_CONSENT)` throws the expected `BpmnError` |
| `NoBypassOfAccessAuthorityTest` | Static/reflective check: no class in `orchestration.internal.workflow` holds a direct reference to `ConnectorRuntime` without also depending on `AccessAuthority` in the same delegate |
| `RetryLoopIT` (Flowable test harness, in-memory H2 or Testcontainers Postgres) | Killing the mock department mid-process moves the step to `PENDING_SOURCE`; advancing the Flowable clock past the timer re-invokes the service task; a second success completes the step |
| `ProcessSurvivesRestartIT` | A process instance parked at the timer catch event, when the `ProcessEngine` is rebuilt (simulating an app restart), still fires the timer and resumes correctly |
| `PinnedConnectorVersionTest` | Publishing `rev-income@4` mid-instance does not change which version an already-running instance calls |
| `IdentityResolutionScannerTest` | The scan's fetch goes through `AccessAuthority` with `purpose = ADMIN_DEDUP` — never calls `ConnectorRuntime` directly |
