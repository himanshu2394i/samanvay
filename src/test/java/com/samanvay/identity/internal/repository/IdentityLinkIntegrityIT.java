package com.samanvay.identity.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One ACTIVE link per citizen and department, one ACTIVE holder per department person ID (a REVOKED link frees both), and an officer
 * confirmation that can neither run twice nor attach a link to a citizen who has been merged away.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityLinkIntegrityIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    UUID citizen(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_citizen (id, status) VALUES (?, ?)", id, status);
        return id;
    }

    void link(UUID citizen, String dept, String token, String status) {
        jdbc.update("INSERT INTO identity_link (id, citizen_id, department_code, local_id_type, local_id_token, provenance, status)"
                + " VALUES (?, ?, ?, 'T', ?, 'CITIZEN_ASSERTED', ?)", UUID.randomUUID(), citizen, dept, token, status);
    }

    @Test
    void a_citizen_has_at_most_one_active_link_per_department() {
        UUID c = citizen("ACTIVE");
        link(c, "REVENUE", "a-" + c, "ACTIVE");
        assertThatThrownBy(() -> link(c, "REVENUE", "b-" + c, "ACTIVE")).hasMessageContaining("uq_identity_link_active_citizen_department");
        link(c, "REVENUE", "b-" + c, "REVOKED");
        link(c, "EDUCATION", "c-" + c, "ACTIVE");
    }

    @Test
    void a_person_id_has_at_most_one_active_holder_and_a_revoked_link_frees_it() {
        UUID first = citizen("ACTIVE");
        UUID second = citizen("ACTIVE");
        String person = "P-" + first;
        link(first, "REVENUE", person, "ACTIVE");
        assertThatThrownBy(() -> link(second, "REVENUE", person, "ACTIVE")).hasMessageContaining("uq_identity_link_active_local_id");
        jdbc.update("UPDATE identity_link SET status = 'REVOKED' WHERE citizen_id = ?", first);
        link(second, "REVENUE", person, "ACTIVE");
    }

    // --- officer confirmation -------------------------------------------------------------------------------------

    UUID candidate(UUID citizen) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_candidate_match (id, citizen_id, department_code, score, features, status)"
                + " VALUES (?, ?, 'REVENUE', 0.800, '{}'::jsonb, 'PENDING')", id, citizen);
        return id;
    }

    int confirm(UUID candidate) {
        return TestHttp.as(TestTokens.reviewer("reviewer-integrity")).post()
                .uri("http://localhost:" + port + "/api/identity/candidates/" + candidate + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("note", "n")).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    int links(UUID citizen) {
        return jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE citizen_id = ? AND status = 'ACTIVE'", Integer.class, citizen);
    }

    @Test
    void confirming_the_same_candidate_twice_is_refused_the_second_time() {
        UUID c = citizen("ACTIVE");
        UUID candidate = candidate(c);
        assertThat(confirm(candidate)).isEqualTo(200);
        assertThat(confirm(candidate)).isEqualTo(409);
        assertThat(links(c)).isEqualTo(1);
    }

    @Test
    void two_reviewers_confirming_at_once_make_one_link() throws Exception {
        UUID c = citizen("ACTIVE");
        UUID candidate = candidate(c);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return confirm(candidate);
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(links(c)).isEqualTo(1);
    }

    @Test
    void a_candidate_whose_citizen_was_merged_away_cannot_be_confirmed() {
        UUID merged = citizen("MERGED");
        UUID candidate = candidate(merged);
        assertThat(confirm(candidate)).isEqualTo(409);
        assertThat(links(merged)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_candidate_match WHERE id = ?", String.class, candidate)).isEqualTo("PENDING");
    }

    @Test
    void a_candidate_for_a_citizen_who_already_has_a_link_there_is_a_conflict_not_a_server_error() {
        UUID c = citizen("ACTIVE");
        link(c, "REVENUE", "existing-" + c, "ACTIVE");
        UUID candidate = candidate(c);
        assertThat(confirm(candidate)).isEqualTo(409);
        assertThat(links(c)).isEqualTo(1);
    }
}
