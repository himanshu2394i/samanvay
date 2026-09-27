package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Many writers released at once - refused API calls (403s are audited from the
 * security filter chain, outside any business transaction; anonymous 401s are
 * only counted) mixed with normal audited writes - must still produce one
 * linear chain: no two entries share a prev_hash, and the whole chain from
 * seq 1 verifies.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditChainConcurrencyIT extends PostgresIntegrationTest {

    private static final int THREADS = 16;
    private static final int ROUNDS = 5;

    @LocalServerPort
    int port;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentRefusedAndNormalAppendsKeepOneLinearChain() throws Exception {
        long before = audit.headSeq();
        String url = "http://localhost:" + port + "/api/audit/head";
        RestClient anonymous = TestHttp.anonymous();
        RestClient citizen = TestHttp.as(TestTokens.citizen("concurrency-citizen"));

        int expectedForbidden = 0;
        int expectedDirect = 0;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch ready = new CountDownLatch(THREADS);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    int kind = t % 3;
                    String subject = "conc-" + round + "-" + t;
                    if (kind == 2) {
                        expectedDirect++;
                    } else if (kind == 1) {
                        expectedForbidden++;
                    }
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return switch (kind) {
                            case 0 -> status(anonymous, url); // 401 via entry point: no chain row
                            case 1 -> status(citizen, url); // 403 via access-denied handler
                            default -> {
                                audit.record(new AuditEntry(
                                        ActorType.SYSTEM, "concurrency-it", "PING", subject, null, null, null, null,
                                        Outcome.ALLOWED, null, Map.of()));
                                yield 0;
                            }
                        };
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (int t = 0; t < THREADS; t++) {
                    int expected = switch (t % 3) {
                        case 0 -> 401;
                        case 1 -> 403;
                        default -> 0;
                    };
                    assertThat(results.get(t).get(30, TimeUnit.SECONDS)).isEqualTo(expected);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        long head = audit.headSeq();
        assertThat(count("action = 'API_FORBIDDEN' AND resource = 'GET /api/audit/head'", before))
                .as("one audit row per 403")
                .isEqualTo(expectedForbidden);
        assertThat(count("action = 'API_UNAUTHENTICATED'", before)).as("anonymous 401s stay out of the chain").isZero();
        assertThat(count("action = 'PING' AND actor_id = 'concurrency-it'", before)).isEqualTo(expectedDirect);

        Integer forks = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT prev_hash FROM audit.audit_entry GROUP BY prev_hash HAVING count(*) > 1) f",
                Integer.class);
        assertThat(forks).as("entries sharing a prev_hash (chain forks)").isZero();

        VerificationResult result = audit.verify(1, head);
        assertThat(result.valid())
                .as("hash chain 1..%d broken at %s (%s)", head, result.failedAtSeq(), result.reason())
                .isTrue();
    }

    private int count(String where, long afterSeq) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_entry WHERE seq > ? AND " + where, Integer.class, afterSeq);
    }

    private static int status(RestClient client, String url) {
        return client.get().uri(url).exchange((req, res) -> res.getStatusCode().value());
    }
}
