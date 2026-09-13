package com.samanvay.orchestration.api;

import java.time.Duration;
import java.util.Map;

public interface WorkflowEngine {
    String deploy(String definitionRef, byte[] definition);

    String start(String processKey, Map<String, Object> variables);

    void signal(String instanceId, String signal, Map<String, Object> variables);

    void scheduleRetry(String instanceId, String activityId, Duration delay);

    EngineState state(String instanceId);
}
