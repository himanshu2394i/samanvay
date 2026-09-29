package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.internal.protocol.ExchangeDeadlineExceededException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Retries live inside the total deadline: an overrun is never retried, other failures are. */
class ResilienceDeadlineTest {

    @Test
    void deadlineOverrunIsNotRetriedOtherFailuresAre() {
        ResilienceRegistries registries = new ResilienceRegistries(3, Duration.ofMillis(10));
        AtomicInteger overruns = new AtomicInteger();
        assertThatThrownBy(() -> registries.execute("DS-DEADLINE-" + System.nanoTime(), () -> {
                    overruns.incrementAndGet();
                    throw new ExchangeDeadlineExceededException("total deadline");
                }))
                .isInstanceOf(ExchangeDeadlineExceededException.class);
        assertThat(overruns.get()).isEqualTo(1);

        AtomicInteger faults = new AtomicInteger();
        assertThatThrownBy(() -> registries.execute("DS-FAULT-" + System.nanoTime(), () -> {
                    faults.incrementAndGet();
                    throw new IllegalStateException("502");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(faults.get()).isEqualTo(3);
    }
}
