package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Claim tokens (V190). A short stale window (2 x 1 s) that a slow department reply outlasts:
 * worker A's claim goes stale and worker B takes it over with a NEW token while A is still
 * waiting on the department. Whatever A then gets back, it can no longer settle the row, so its
 * result is thrown away and exactly one CONSENT_CHECK_LOST_CLAIM row is written.
 *
 * <p>B is held inside its own department call until A has settled, so without the token match A
 * would find the row still PENDING and settle B's claim.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "samanvay.connector.timeout=PT1S", // stale window 2 s
    "samanvay.connector.total-timeout=PT0.3S",
    "samanvay.connector.stale-margin=PT0.5S"
})
class ConsentLostClaimIT extends OneCheckITSupport {

    record Worker(UUID grant, CountDownLatch inFlight, CountDownLatch release) {}

    @Test
    void slowReplyAfterTakeoverIsDiscardedAndTheNewOwnerKeepsTheCheck() throws Exception {
        runTakeover(Mode.A_SUCCEEDS_LATE);
    }

    @Test
    void failedReplyAfterTakeoverDoesNotReleaseTheNewOwnersClaim() throws Exception {
        runTakeover(Mode.A_FAILS_LATE);
    }

    enum Mode { A_SUCCEEDS_LATE, A_FAILS_LATE }

    private void runTakeover(Mode mode) throws Exception {
        assertThat(connectorTimeout).isEqualTo(Duration.ofSeconds(1));
        String app = app();
        CountDownLatch aInFlight = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bInFlight = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        AtomicReference<UUID> grantA = new AtomicReference<>();
        AtomicReference<UUID> grantB = new AtomicReference<>();
        department.onCall = grant -> {
            if (department.calls.get() == 1) { // worker A: the slow department reply
                grantA.set(grant.id());
                aInFlight.countDown();
                await(releaseA, 20);
                if (mode == Mode.A_FAILS_LATE) {
                    throw HttpServerErrorException.create(
                            org.springframework.http.HttpStatus.BAD_GATEWAY, "late 502", null, null, null);
                }
                return success();
            }
            grantB.set(grant.id()); // worker B
            bInFlight.countDown();
            await(releaseB, 20);
            return success();
        };
        long before = headSeq();

        CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> run(app));
        assertThat(aInFlight.await(10, TimeUnit.SECONDS)).isTrue();
        UUID tokenA = (UUID) usageRows(app).get(0).get("claim_token");

        Thread.sleep(connectorTimeout.multipliedBy(2).toMillis() + 300); // A's claim is now stale
        CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> run(app));
        assertThat(bInFlight.await(10, TimeUnit.SECONDS)).as("B reclaimed the stale claim").isTrue();
        Map<String, Object> reclaimed = usageRows(app).get(0);
        UUID tokenB = (UUID) reclaimed.get("claim_token");
        assertThat(tokenB).as("a takeover writes a new token").isNotEqualTo(tokenA);
        assertThat(reclaimed.get("grant_id")).isEqualTo(grantB.get());

        releaseA.countDown(); // A's slow reply arrives while B's claim is PENDING
        String outcomeA = a.get(20, TimeUnit.SECONDS);
        releaseB.countDown();
        String outcomeB = b.get(20, TimeUnit.SECONDS);

        assertThat(department.calls.get()).as("the department was called twice").isEqualTo(2);
        assertThat(outcomeA).as("A's result is thrown away")
                .isEqualTo(mode == Mode.A_SUCCEEDS_LATE ? "CLAIM_LOST" : "THREW:BadGateway");
        assertThat(outcomeB).as("exactly one result kept: B's").isEqualTo("COMPLETED");
        List<Map<String, Object>> lost = audit(before, "CONSENT_CHECK_LOST_CLAIM");
        assertThat(lost).as("exactly one lost-claim row").singleElement().satisfies(r -> {
            assertThat(r.get("grant_id")).isEqualTo(grantA.get());
            assertThat(r.get("reason")).isEqualTo("RESULT_DISCARDED");
            assertThat(r.get("meta").toString()).contains(mode == Mode.A_SUCCEEDS_LATE ? "MARK_USED" : "RELEASE");
        });
        assertThat(audit(before, "CONSENT_CHECK_RELEASED")).as("A released nothing").isEmpty();
        assertThat(usageRows(app)).singleElement().satisfies(r -> {
            assertThat(r.get("state")).isEqualTo("USED");
            assertThat(r.get("claim_token")).as("USED with B's token").isEqualTo(tokenB);
            assertThat(r.get("grant_id")).isEqualTo(grantB.get());
        });
    }

    private String run(String app) {
        try {
            return delegate.execute(fetch(app), inputs());
        } catch (RuntimeException e) {
            return "THREW:" + e.getClass().getSimpleName();
        }
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
