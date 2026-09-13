package com.samanvay.orchestration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class JourneyServiceStubEngineTest {

    @Test
    void stubEngineIsARealPort() {
        WorkflowEngine stub = new WorkflowEngine() {
            private final Map<String, Map<String, Object>> instances = new ConcurrentHashMap<>();

            @Override
            public String deploy(String definitionRef, byte[] definition) {
                return definitionRef;
            }

            @Override
            public String start(String processKey, Map<String, Object> variables) {
                String id = UUID.randomUUID().toString();
                instances.put(id, variables);
                return id;
            }

            @Override
            public void signal(String instanceId, String signal, Map<String, Object> variables) {}

            @Override
            public void scheduleRetry(String instanceId, String activityId, Duration delay) {}

            @Override
            public EngineState state(String instanceId) {
                return instances.containsKey(instanceId) ? EngineState.active(java.util.List.of(instanceId)) : EngineState.completed();
            }
        };
        String id = stub.start("scholarship", Map.of("citizenId", "x"));
        assertThat(stub.state(id).done()).isFalse();
    }
}
