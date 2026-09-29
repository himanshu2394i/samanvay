package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The connector's TOTAL deadline: headers arrive at once and the body trickles one byte every
 * 100 ms (~4.7 s in all, each byte would reset a read timeout). The call must be cut off at the
 * 0.8 s total limit, and the exchange actively cancelled so the department stops sending.
 */
class DeadlineHttpTest {

    static final long GAP = 100;
    static final Duration TOTAL = Duration.ofMillis(800);

    @Test
    void trickledBodyIsCutOffAtTheTotalLimitAndTheExchangeCancelled() throws Exception {
        try (TricklingDepartment dept = new TricklingDepartment(GAP)) {
            DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(5), TOTAL);
            long start = System.nanoTime();
            assertThatThrownBy(() -> http.get(dept.uri("/slow")))
                    .isInstanceOf(ExchangeDeadlineExceededException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(tookMs).as("cut off at the total limit, not after the whole trickle")
                    .isBetween(TOTAL.toMillis() - 50, TOTAL.toMillis() + 700);
            assertThat(tookMs).isLessThan(TricklingDepartment.trickleMillis(GAP) / 2);
            // cancel(true) aborted the transfer: the department's next writes fail.
            assertThat(dept.trickleEnded.await(TricklingDepartment.trickleMillis(GAP) + 3000, TimeUnit.MILLISECONDS))
                    .isTrue();
            assertThat(dept.trickleCompleted).as("the department never got to send the whole body").isFalse();
            assertThat(dept.trickleCutOff).isTrue();

            assertThat(http.get(dept.uri("/fast"))).contains("annualIncome");
        }
    }

    @Test
    void anOpenExchangeDeadlineIsSharedByEveryAttempt() throws Exception {
        try (TricklingDepartment dept = new TricklingDepartment(GAP)) {
            DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(5), Duration.ofSeconds(30));
            try (ExchangeDeadline d = ExchangeDeadline.start(Duration.ofMillis(500))) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> http.get(dept.uri("/slow"))).isInstanceOf(ExchangeDeadlineExceededException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
                assertThatThrownBy(() -> http.get(dept.uri("/fast")))
                        .as("a later attempt gets no fresh budget")
                        .isInstanceOf(ExchangeDeadlineExceededException.class)
                        .hasMessageContaining("already passed");
            }
            assertThat(ExchangeDeadline.remaining()).isEmpty();
        }
    }
}
