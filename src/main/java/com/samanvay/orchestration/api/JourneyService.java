package com.samanvay.orchestration.api;

import com.samanvay.shared.PrincipalRef;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface JourneyService {
    /**
     * @param initiatedBy who started the journey, from the caller's token. Recorded
     *     on every access grant (and so on every DATA_ACCESSED audit entry) this
     *     start produces.
     */
    JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission, PrincipalRef initiatedBy);

    void signal(UUID instanceId, String signalName, JsonNode payload);

    void cancel(UUID instanceId, String reason);

    JourneyState state(UUID instanceId);

    /** Re-fetches non-completed steps; new grants are attributed to {@code initiatedBy} (the retrying officer). */
    void retryPending(UUID instanceId, PrincipalRef initiatedBy);

    /** Data-source codes this journey fetches from (for department-client scope checks). */
    java.util.Set<String> dataSources(String journeyCode);

    java.util.List<JourneyExceptionView> openExceptions();
}
