package com.samanvay.identity.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.ReviewerRequiredException;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * Pins all three no-auto-link layers, each independently of the others:
 * <ol>
 *   <li>edge - only a REVIEWER token reaches confirm/reject (route rule);
 *   <li>service - {@code IdentityResolution.confirm} refuses a non-reviewer
 *       security context even when called in-process;
 *   <li>database - {@code chk_no_auto_probabilistic_link} rejects an ACTIVE +
 *       PROBABILISTIC link whatever the application does.
 * </ol>
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoAutoLinkConstraintIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdentityResolution resolution;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void layer1EdgeOnlyReviewerTokensReachConfirm() {
        UUID candidate = pendingCandidate();
        for (String token : new String[] {
            TestTokens.officer("o"), TestTokens.admin("a"), TestTokens.citizen("c"), TestTokens.department("d", "revenue-rest-mock")
        }) {
            assertThat(confirm(TestHttp.as(token), candidate)).isEqualTo(403);
        }
        assertThat(confirm(TestHttp.anonymous(), candidate)).isEqualTo(401);
        assertThat(status(candidate)).isEqualTo("PENDING");
        assertThat(activeLinks(candidate)).isZero();

        assertThat(confirm(TestHttp.as(TestTokens.reviewer("reviewer-layer1")), candidate)).isEqualTo(200);
        assertThat(status(candidate)).isEqualTo("CONFIRMED");
    }

    @Test
    void layer2ServiceRefusesNonReviewerEvenInProcess() {
        UUID candidate = pendingCandidate();
        assertThatThrownBy(() -> resolution.confirm(candidate, "no context")).isInstanceOf(ReviewerRequiredException.class);
        var officer = new TestingAuthenticationToken("officer-inproc", null, "ROLE_OFFICER");
        officer.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(officer);
        assertThatThrownBy(() -> resolution.confirm(candidate, "officer")).isInstanceOf(ReviewerRequiredException.class);
        assertThatThrownBy(() -> resolution.reject(candidate, "officer")).isInstanceOf(ReviewerRequiredException.class);
        assertThat(status(candidate)).isEqualTo("PENDING");
    }

    @Test
    void layer3DatabaseRejectsActiveProbabilisticLink() {
        UUID citizen = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_citizen (id, status) VALUES (?, 'ACTIVE')", citizen);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO identity_link
                        (id, citizen_id, department_code, local_id_type, local_id_token, provenance, status)
                        VALUES (?, ?, 'REVENUE', 'X', ?, 'PROBABILISTIC', 'ACTIVE')
                        """,
                        UUID.randomUUID(),
                        citizen,
                        "tok-" + citizen))
                .hasMessageContaining("chk_no_auto_probabilistic_link");
    }

    private int confirm(RestClient http, UUID candidate) {
        return http.post()
                .uri("http://localhost:" + port + "/api/identity/candidates/" + candidate + "/confirm")
                .header("X-Roles", "IDENTITY_REVIEWER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reviewerId", "spoofed", "note", "n"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private UUID pendingCandidate() {
        UUID citizen = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_citizen (id, status) VALUES (?, 'ACTIVE')", citizen);
        UUID candidate = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_candidate_match (id, citizen_id, department_code, score, features, status) "
                        + "VALUES (?, ?, 'REVENUE', 0.800, '{}'::jsonb, 'PENDING')",
                candidate,
                citizen);
        return candidate;
    }

    private String status(UUID candidate) {
        return jdbc.queryForObject("SELECT status FROM identity_candidate_match WHERE id = ?", String.class, candidate);
    }

    private int activeLinks(UUID candidate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity_link l JOIN identity_candidate_match c ON c.citizen_id = l.citizen_id "
                        + "WHERE c.id = ? AND l.status = 'ACTIVE'",
                Integer.class,
                candidate);
    }
}
