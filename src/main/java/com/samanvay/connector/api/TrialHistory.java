package com.samanvay.connector.api;

import java.time.Instant;
import java.util.Optional;

/** When a connector was last given an onboarding trial, and how it went. Read-only; carries no fetched data. */
public interface TrialHistory {

    Optional<Trial> last(String connectorRef);

    /** @param outcome SUCCESS, NOT_FOUND, UNAVAILABLE, INVALID or ERROR */
    record Trial(Instant at, String outcome) {}
}
