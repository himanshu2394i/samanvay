package com.samanvay.orchestration.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only view of how one journey is running, for the staff journey page. Carries no citizen data: instance ids, states,
 * start times and the connector versions each instance pinned.
 */
public interface JourneyActivity {

    /**
     * @param since counts {@code startedSince} from this instant
     * @param recentLimit how many of the newest instances to list
     */
    Activity activity(String journeyCode, Instant since, int recentLimit);

    /**
     * @param running instances still moving (SUBMITTED, PARTIALLY_VERIFIED, VERIFIED), all time
     * @param completed APPROVED instances, all time
     * @param failed REJECTED instances, all time
     * @param startedSince instances of any state started since the cutoff
     * @param recent the newest instances first
     */
    record Activity(long running, long completed, long failed, long startedSince, List<Recent> recent) {}

    /** @param pinnedVersions connector id to the version the instance pinned at start */
    record Recent(UUID instanceId, String status, Instant startedAt, Map<String, Integer> pinnedVersions) {}
}
