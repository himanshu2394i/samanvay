package com.samanvay.orchestration.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface JourneyService {
    JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission);

    void signal(UUID instanceId, String signalName, JsonNode payload);

    void cancel(UUID instanceId, String reason);

    JourneyState state(UUID instanceId);

    void retryPending(UUID instanceId);

    java.util.List<JourneyExceptionView> openExceptions();
}
