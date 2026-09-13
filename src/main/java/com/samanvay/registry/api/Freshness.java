package com.samanvay.registry.api;

import java.time.Duration;
import java.time.Instant;

public record Freshness(FreshnessMode mode, Instant asOf) {
    private static final Duration STALE_THRESHOLD = Duration.ofHours(6);

    public boolean isStale() {
        return mode == FreshnessMode.BATCH && Duration.between(asOf, Instant.now()).compareTo(STALE_THRESHOLD) > 0;
    }
}
