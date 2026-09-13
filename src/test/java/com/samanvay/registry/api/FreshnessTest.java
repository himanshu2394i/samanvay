package com.samanvay.registry.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FreshnessTest {

    @Test
    void batchOlderThanSixHoursIsStaleRealtimeNeverIs() {
        assertThat(new Freshness(FreshnessMode.BATCH, Instant.now().minusSeconds(7 * 3600)).isStale()).isTrue();
        assertThat(new Freshness(FreshnessMode.REALTIME, Instant.now().minusSeconds(7 * 3600)).isStale()).isFalse();
    }
}
