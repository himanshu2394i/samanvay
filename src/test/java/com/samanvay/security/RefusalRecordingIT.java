package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
 * Anonymous 401s cost no chain rows (only a log line and a counter); 403s are
 * chained with the matched route template, never the raw, caller-chosen URL.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefusalRecordingIT extends PostgresIntegrationTest {

    static final String ATTACKER_SEGMENT = "ATTACKER-DROP-TABLE-%3Cscript%3Ealert(1)%3C-script%3E";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void burstOfAnonymous401sAddsNoChainRowsAndIsCounted() throws Exception {
        long headBefore = head();
        double countedBefore = unauthenticatedCount();
        List<RestClient> callers = List.of(
                TestHttp.anonymous(),
                TestHttp.as("not-a-jwt"),
                TestHttp.as(TestTokens.expiredOfficer("burst")),
                TestHttp.as(TestTokens.forgedOfficer("burst")));
        List<String> paths = List.of(
                "/api/audit/head",
                "/api/journeys/instances/" + ATTACKER_SEGMENT,
                "/api/identity/candidates/" + ATTACKER_SEGMENT + "/confirm",
                "/api/nothing-here/" + ATTACKER_SEGMENT);
        int burst = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                RestClient caller = callers.get(i % callers.size());
                String path = paths.get(i % paths.size());
                boolean post = path.endsWith("/confirm");
                results.add(pool.submit(() -> {
                    go.await();
                    return post
                            ? caller.post().uri(uri(path)).exchange((rq, rs) -> rs.getStatusCode().value())
                            : caller.get().uri(uri(path)).exchange((rq, rs) -> rs.getStatusCode().value());
                }));
            }
            go.countDown();
            for (Future<Integer> r : results) {
                assertThat(r.get(30, TimeUnit.SECONDS)).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unauthenticatedCount() - countedBefore).as("counter").isEqualTo(burst);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_entry WHERE seq > ? AND (action = 'API_UNAUTHENTICATED' "
                                + "OR actor_type = 'ANONYMOUS' OR resource LIKE '%ATTACKER%')",
                        Integer.class,
                        headBefore))
                .as("chain rows from the burst")
                .isZero();
        // the counter is tagged by route template too, never by raw path
        assertThat(meters.find("samanvay.api.unauthenticated").counters())
                .extracting(c -> c.getId().getTag("route"))
                .contains("GET /api/journeys/instances/{id}", "POST /api/identity/candidates/{id}/confirm", "GET UNMATCHED")
                .noneMatch(route -> route.contains("ATTACKER"));
    }

    @Test
    void forbiddenCallStoresTheRouteTemplateNotTheRawUrl() {
        String citizen = "cit-403-" + System.nanoTime();
        int status = TestHttp.as(TestTokens.citizen(citizen)).get()
                .uri(uri("/api/journeys/instances/" + ATTACKER_SEGMENT))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(403);
        int unmatched = TestHttp.as(TestTokens.citizen(citizen)).get()
                .uri(uri("/api/nothing-here/" + ATTACKER_SEGMENT))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(unmatched).isEqualTo(403);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT resource, actor_type, reason FROM audit.audit_entry WHERE action = 'API_FORBIDDEN' AND actor_id = ? ORDER BY seq",
                citizen);
        assertThat(rows).extracting(r -> r.get("resource"))
                .containsExactly("GET /api/journeys/instances/{id}", "GET UNMATCHED");
        assertThat(rows).extracting(r -> r.get("actor_type")).containsOnly("CITIZEN");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_entry WHERE resource LIKE '%ATTACKER%' OR reason LIKE '%ATTACKER%'",
                        Integer.class))
                .isZero();
    }

    private double unauthenticatedCount() {
        return meters.find("samanvay.api.unauthenticated").counters().stream().mapToDouble(Counter::count).sum();
    }

    private long head() {
        Long head = jdbc.queryForObject("SELECT coalesce(max(seq), 0) FROM audit.audit_entry", Long.class);
        return head == null ? 0 : head;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
