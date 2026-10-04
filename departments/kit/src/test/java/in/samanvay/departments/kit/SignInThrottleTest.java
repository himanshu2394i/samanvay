package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SignInThrottleTest {

    static final class Tick extends Clock {
        Instant now = Instant.parse("2026-10-04T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    final Tick clock = new Tick();
    final SignInThrottle throttle = new SignInThrottle(clock);

    @Test
    void a_mobile_is_blocked_after_five_failures_in_ten_minutes_and_free_again_afterwards() {
        for (int i = 0; i < 4; i++) {
            throttle.credentialsFailed("9000000001", "10.0.0." + i);
        }
        assertThat(throttle.credentialsBlocked("9000000001", "10.0.0.9")).isFalse();
        throttle.credentialsFailed("9000000001", "10.0.0.5");
        assertThat(throttle.credentialsBlocked("9000000001", "10.0.0.9")).isTrue();
        assertThat(throttle.credentialsBlocked("9000000002", "10.0.0.9")).as("another mobile is unaffected").isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(10)).plusSeconds(1);
        assertThat(throttle.credentialsBlocked("9000000001", "10.0.0.9")).isFalse();
    }

    @Test
    void one_address_is_blocked_after_too_many_failures_whatever_mobiles_it_tries() {
        for (int i = 0; i < SignInThrottle.PER_IP; i++) {
            throttle.credentialsFailed("90000000" + i, "10.1.1.1");
        }
        assertThat(throttle.credentialsBlocked("9111111111", "10.1.1.1")).isTrue();
        assertThat(throttle.credentialsBlocked("9111111111", "10.1.1.2")).isFalse();
    }

    @Test
    void a_successful_sign_in_clears_that_mobiles_failures() {
        for (int i = 0; i < 4; i++) {
            throttle.credentialsFailed("9000000001", "10.0.0.1");
        }
        throttle.credentialsOk("9000000001");
        throttle.credentialsFailed("9000000001", "10.0.0.1");
        assertThat(throttle.credentialsBlocked("9000000001", "10.0.0.1")).isFalse();
    }

    @Test
    void a_ticket_allows_five_wrong_codes_then_is_dead_even_for_the_right_code() {
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.codeBlocked("ticket-A", "10.0.0." + i)).isFalse();
            throttle.codeFailed("ticket-A", "10.0.0." + i);
        }
        assertThat(throttle.codeBlocked("ticket-A", "10.0.0.99")).isTrue();
        assertThat(throttle.codeBlocked("ticket-B", "10.0.0.2")).isFalse();
    }

    @Test
    void a_ticket_can_be_used_once() {
        assertThat(throttle.useTicket("ticket-A")).isTrue();
        assertThat(throttle.useTicket("ticket-A")).isFalse();
        assertThat(throttle.useTicket("ticket-B")).isTrue();
    }

    @Test
    void memory_is_bounded_and_used_tickets_are_forgotten_once_they_could_not_be_valid_anyway() {
        for (int i = 0; i < SignInThrottle.MAX_KEYS + 50; i++) {
            throttle.credentialsFailed("m" + i, "ip" + i);
        }
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(SignInThrottle.MAX_KEYS);
        assertThat(throttle.useTicket("old")).isTrue();
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(throttle.useTicket("trigger-prune")).isTrue();
        assertThat(throttle.usedTickets()).isEqualTo(1);
    }
}
