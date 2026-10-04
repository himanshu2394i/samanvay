package com.samanvay.catalog.api;

import java.time.Duration;
import java.util.Optional;

/**
 * What the catalog needs to know about trial fetches, which only the connector module can run (it is the one that talks to a
 * department). A trial is a real call to the department for its published FAKE sample person; its outcome is stored durably.
 * Publishing a connector requires a recent successful one, held here, not claimed by the client.
 */
public interface TrialGate {

    /**
     * Runs a trial for the connector's own sample person (the one its manifest declares) and records it.
     *
     * @return the outcome, or empty when the connector declares no sample person (nothing is called)
     */
    Optional<TrialSummary> runSample(String connectorRef);

    /** Did the connector's LAST recorded trial succeed, no longer than {@code window} ago? */
    boolean succeededWithin(String connectorRef, Duration window);

    /** @param outcome SUCCESS, NOT_FOUND, UNAVAILABLE, INVALID or ERROR; {@code detail} is a short reason, never citizen data */
    record TrialSummary(String outcome, String detail) {
        public boolean ok() {
            return "SUCCESS".equals(outcome);
        }
    }
}
