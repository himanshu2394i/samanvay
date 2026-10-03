package com.samanvay.shared.security;

import java.util.Optional;

/**
 * Names the department that runs a journey (its requester). Implemented by {@code catalog}; declared here so any module can scope a
 * department caller to its own journeys without importing catalog internals.
 */
public interface JourneyRequesters {

    /** The requester department of the journey, empty when there is no such journey. */
    Optional<String> requesterOf(String journeyCode);
}
