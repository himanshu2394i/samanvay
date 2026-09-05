# Module: `orchestration`

| | |
|---|---|
| **Plane** | Data — but sits at the authorization boundary |
| **Owner** | E |
| **Phase** | 1 (Journey 1) → 2 (parallel fan-out, degraded mode, resolution scans) |
| **Depends on** | `consent` (`AccessAuthority`), `connector`, `catalog`, `identity` (`submitCandidate`), `audit` |
| **Depended on by** | Nothing. It is driven by the API layer and publishes events |
| **HLD context** | [§6.2](../HLD.md#62-journey-execution--parallel-fan-out-and-degraded-mode) · [§7](../HLD.md#7-connector-subsystem) |

---

## 1. Purpose

Execute a citizen journey: request the right data from the right departments in the right
order, cope with departments that are slow, down or batch-only, and drive an application to
a conclusion.

Orchestration **interprets business workflow and coordinates authorized actions**. It is
not merely a data mover, which is why it sits at the authorization boundary rather than
below it — but it holds no authority of its own. Every fetch it performs is authorized by
`AccessAuthority` first.

```
                 CONTROL PLANE
        identity · consent · registry · catalog
                       │  authorization
                       ▼
                 ORCHESTRATION
                       │
              ┌────────┴────────┐
              ▼                 ▼
         Connector A       Connector B
              ▼                 ▼
         Department A      Department B
                  DATA PLANE
```

## 2. Responsibilities

### Owns

- The `WorkflowEngine` port and its Flowable implementation
- Journey execution: BPMN process instances, service task delegates
- Parallel fan-out and join
- Degraded mode: `PENDING_SOURCE`, retry scheduling, escalation
- Compensation for partially completed multi-department operations
- Driving identity resolution scans

### Explicitly not responsible for

- **Authorizing anything.** It asks `AccessAuthority` and obeys the answer.
- **Knowing protocols.** It calls `ConnectorRuntime`; it has never heard of SOAP.
- **Owning application status for display.** It publishes events; `tracking` maintains the
  citizen-facing view.
- **Defining journeys.** `catalog` holds the BPMN and policy.

## 3. Public interface — `com.samanvay.orchestration.api`

```java
public interface JourneyService {
    JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission);
    void signal(UUID instanceId, String signalName, JsonNode payload);
    void cancel(UUID instanceId, String reason);
    JourneyState state(UUID instanceId);
}

/** The port. Flowable behind it; a YAML DAG executor is the documented fallback. */
public interface WorkflowEngine {
    String  deploy(String definitionRef, byte[] definition);
    String  start(String processKey, Map<String, Object> variables);
    void    signal(String instanceId, String signal, Map<String, Object> variables);
    void    scheduleRetry(String instanceId, String activityId, Duration delay);
    EngineState state(String instanceId);
}

/** Officer-facing. */
public interface ExceptionQueue {
    Page<ExceptionItem> open(ExceptionFilter f, Pageable p);
    void resolve(UUID itemId, Resolution resolution, String officerId);
}
```

## 4. Data owned

| Table | Notes |
|---|---|
| `orchestration_instance` | Journey instance ↔ engine process instance, journey code + pinned connector versions |
| `orchestration_step_state` | Per-step status, attempts, last failure, next retry time |
| Flowable's own tables | Owned by the engine, namespaced by its prefix. Not queried directly by other modules |

**No other module queries Flowable tables.** That coupling would make the
`WorkflowEngine` port a fiction.

## 5. Events

### Published

| Event | Consumed by |
|---|---|
| `JourneyStarted` | `tracking`, `notifications` |
| `StepCompleted` / `StepFailed` | `tracking`, observability |
| `StepPendingSource` | `tracking`, `notifications` (citizen sees "waiting on Revenue") |
| `ApplicationStateChanged` | `tracking`, `notifications` |
| `SlaBreached` | `notifications`, SLA dashboard |
| `ManualUploadRequested` | `notifications` |

### Consumed

| Event | Reaction |
|---|---|
| `ConsentRevoked` | Affected steps → `AUTHORIZATION_WITHDRAWN`; officer notified |
| `PointerExpired` | Re-evaluate dependent in-flight steps |
| `ConnectorFailed` | Feeds retry/escalation decisions |

`tracking` and `notifications` are **event consumers only**. `orchestration` never calls
them — a direct call would need their types and close a dependency cycle.

## 6. Key decisions

### Flowable behind a port

BPMN 2.0 is standards-based (the problem statement asks for exactly that), and brings
parallel gateways, timers, retries, compensation, persistence and history without being
written. The port exists because it is the highest-risk dependency: a YAML DAG executor
behind the same interface is a documented ~2-day fallback.

Journey 3 becomes *"drop in a `.bpmn` file"*, which is only credible because the engine is
real.

### Every fetch goes through `AccessAuthority` first

```
Workflow step
     ▼
AccessAuthority ──► DENIED ──► step records the reason, journey handles it
     │
   GRANTED (signed grant)
     ▼
ConnectorRuntime ──► Department
```

Not `Flowable → Connector → Department`. A workflow that could reach a department directly
would be a consent bypass, and workflows are configuration — the thing most likely to be
edited by someone who has not read this document.

### `PARTIALLY_VERIFIED` is a state, not an error

One department being down must not halt a citizen's file. That is precisely the
fragmentation the problem statement describes; reproducing it inside the platform would be
a design failure.

The application continues through steps that do not depend on the missing input; the
missing step becomes `PENDING_SOURCE` with a scheduled retry.

### Retries are engine timers, not cron

They survive restart, are visible in the process instance, and need no separate scheduler.

**Open issue:** Resilience4j retries inside `connector` and Flowable timers here are two
retry layers that multiply. One must be authoritative — see §9.

### The manual-upload fallback is the honest path

It is what happens in a real government office when a system is down. It is audited
differently (`SOURCE: CITIZEN_UPLOAD` vs `SOURCE: REVENUE_API`), so the provenance of every
field stays visible to the approving officer.

### Orchestration drives identity resolution scans

`identity` must not depend on `connector` (that closes a cycle). So this module fetches
department records under an admin-purpose grant and calls `identity.submitCandidate(...)`.
Correct under **P2**: moving data is the data plane's job.

### Journeys pin connector versions

An in-flight application must not change behaviour because someone published
`rev-income@4` this morning.

## 7. Failure modes

| Failure | Behaviour |
|---|---|
| One department unavailable | Step → `PENDING_SOURCE`; application → `PARTIALLY_VERIFIED`; retry with backoff |
| All departments unavailable | Application accepted and queued; citizen informed; nothing lost |
| Access denied — no consent | Step blocks with an actionable consent request to the citizen |
| Access denied — consent revoked mid-flight | Step → `AUTHORIZATION_WITHDRAWN`; officer notified |
| Retry budget exhausted | Escalate to exception queue; offer manual upload |
| SLA breached | `SlaBreached` published; visible on the compliance dashboard with a reason |
| Engine unavailable at startup | Application refuses to start — fail fast, not silently degraded |
| Partial multi-department `SUBMIT` | Compensation path; `submission_attempt` prevents duplicates on replay |

## 8. Acceptance criteria

- [ ] No workflow step can reach `ConnectorRuntime` without an `AccessGrant` from `AccessAuthority`
- [ ] Killing a department mid-journey yields `PARTIALLY_VERIFIED`, not `FAILED`
- [ ] The application continues through independent steps while one is `PENDING_SOURCE`
- [ ] Retry recovers automatically once the department returns
- [ ] Revoking consent mid-flight moves the affected step to `AUTHORIZATION_WITHDRAWN` within the grant TTL
- [ ] SLA breach appears on the dashboard with a cause
- [ ] Journey 3 runs from a BPMN file plus configuration with **zero Java changes**
- [ ] No module outside `orchestration` queries a Flowable table
- [ ] Swapping `WorkflowEngine` to a stub implementation keeps `JourneyService` tests passing

The last item is how the port proves it is real rather than decorative.

## 9. Open questions for LLD

- **Retry authority.** Resilience4j (in-call, fast, seconds) versus Flowable timers
  (cross-restart, slow, minutes). Proposal: Resilience4j owns transient in-call retries;
  Flowable owns the long-horizon schedule; the connector reports `retryable` and does not
  itself loop for minutes. Needs to be fixed in the LLD, not discovered in testing.
- Compensation scope: which multi-department operations genuinely need it in Journeys 1–3,
  or whether `submission_attempt` idempotency is sufficient for all of them.
- Where SLA clocks live — here or in `tracking`. They are computed here and displayed
  there; ownership must be singular.
- BPMN structure conventions: one process per journey versus a shared subprocess for
  "fetch a category under consent" reused across journeys. The latter is likely correct and
  strengthens the Journey 3 claim.
