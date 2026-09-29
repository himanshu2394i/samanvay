package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.samanvay.audit.api.AuditService;
import com.samanvay.consent.internal.repository.ConsentUsageRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup refuses a stale window that a still-running connector exchange could outlast. */
class ConnectorTimingCheckTest {

    static Duration s(double seconds) {
        return Duration.ofMillis((long) (seconds * 1000));
    }

    @Test
    void goodConfigBoots() {
        // defaults: stale 2 x 10 s = 20 s > 10 s total + 0.5 s retry wait + 5 s margin
        assertThatCode(() -> ConnectorTimingCheck.validate(s(20), s(10), s(5), 3, s(0.5))).doesNotThrowAnyException();
        assertThatCode(() -> service(s(10), s(10), s(5), 3, s(0.5))).doesNotThrowAnyException();
    }

    @Test
    void atOrBelowTheBoundaryFails() {
        assertThatThrownBy(() -> ConnectorTimingCheck.validate(s(15.5), s(10), s(5), 3, s(0.5)))
                .as("exactly at total + wait + margin")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale window");
        assertThatThrownBy(() -> ConnectorTimingCheck.validate(s(15), s(10), s(5), 1, s(0.5)))
                .as("exactly at total + margin, no retries")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConnectorTimingCheck.validate(s(12), s(10), s(5), 1, s(0.5)))
                .as("below")
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ConnectorTimingCheck.validate(s(15.001), s(10), s(5), 1, s(0.5)))
                .as("just above")
                .doesNotThrowAnyException();
    }

    @Test
    void retriesPushTheTotalOver() {
        // 14 s total + 5 s margin fits a 20 s stale window without retries ...
        assertThatCode(() -> ConnectorTimingCheck.validate(s(20), s(14), s(5), 1, s(2))).doesNotThrowAnyException();
        // ... but a 2 s retry backoff can overshoot the deadline: 14 + 2 + 5 = 21 > 20.
        assertThatThrownBy(() -> ConnectorTimingCheck.validate(s(20), s(14), s(5), 3, s(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry wait");
    }

    @Test
    void theUsageServiceRefusesToStartWithABadConfig() {
        // timeout 5 s -> stale window 10 s, not > 10 s total + 0.5 s + 5 s
        assertThatThrownBy(() -> service(s(5), s(10), s(5), 3, s(0.5))).isInstanceOf(IllegalStateException.class);
    }

    private static ConsentUsageService service(
            Duration timeout, Duration total, Duration margin, int attempts, Duration wait) {
        return new ConsentUsageService(
                mock(ConsentUsageRepository.class), mock(AuditService.class), timeout, total, margin, attempts, wait);
    }
}
