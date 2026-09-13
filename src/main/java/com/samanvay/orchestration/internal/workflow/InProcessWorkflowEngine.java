package com.samanvay.orchestration.internal.workflow;

import com.samanvay.orchestration.api.EngineState;
import com.samanvay.orchestration.api.WorkflowEngine;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Flowable is the intended engine (HLD §15). This in-process port implementation
 * runs Journey 1 without a Flowable Boot 4 starter. Swap the bean; JourneyService stays.
 */
@Component
class InProcessWorkflowEngine implements WorkflowEngine {

    private final Map<String, byte[]> deployments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> instances = new ConcurrentHashMap<>();

    @Override
    public String deploy(String definitionRef, byte[] definition) {
        deployments.put(definitionRef, definition);
        return definitionRef;
    }

    @Override
    public String start(String processKey, Map<String, Object> variables) {
        String id = "wf-" + UUID.randomUUID();
        instances.put(id, new ConcurrentHashMap<>(variables));
        return id;
    }

    @Override
    public void signal(String instanceId, String signal, Map<String, Object> variables) {
        instances.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>()).putAll(variables);
    }

    @Override
    public void scheduleRetry(String instanceId, String activityId, Duration delay) {
        // ponytail: Flowable timers would persist this; in-process engine leaves retry to JourneyService
    }

    @Override
    public EngineState state(String instanceId) {
        return instances.containsKey(instanceId) ? EngineState.active(java.util.List.of("running")) : EngineState.completed();
    }
}
