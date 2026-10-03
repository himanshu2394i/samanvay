package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Actor comes from the token only: body {@code reviewerId}, {@code X-Roles} and
 * {@code X-Auth-Jti} are ignored; token validation rejects forged, expired and
 * foreign-issuer tokens; every refused call leaves one audit entry.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityBehaviourIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CitizenProfiles profiles;

    @Test
    void reviewerConfirmRecordsTokenSubjectAndIgnoresBodyReviewerIdAndXRoles() {
        UUID candidate = pendingCandidate();
        ResponseEntity<Map> res = TestHttp.as(TestTokens.reviewer("reviewer-sub-9"))
                .post()
                .uri(url("/api/identity/candidates/" + candidate + "/confirm"))
                .header("X-Roles", "OFFICER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reviewerId", "mallory", "note", "same person"))
                .retrieve()
                .toEntity(Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        assertThat(jdbc.queryForObject(
                        "SELECT reviewed_by FROM identity_candidate_match WHERE id = ?", String.class, candidate))
                .isEqualTo("reviewer-sub-9");
        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT actor_type, actor_id FROM audit.audit_entry WHERE action = 'CANDIDATE_CONFIRMED' AND resource = ?",
                candidate.toString());
        assertThat(entry.get("actor_id")).isEqualTo("reviewer-sub-9");
        assertThat(entry.get("actor_type")).isEqualTo("REVIEWER");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_entry WHERE actor_id = 'mallory'", Integer.class))
                .isZero();
    }

    @Test
    void reviewerRejectIsAuditedAsReviewer() {
        UUID candidate = pendingCandidate();
        int status = post(TestHttp.as(TestTokens.reviewer("reviewer-sub-10")),
                "/api/identity/candidates/" + candidate + "/reject", Map.of("note", "different person"));
        assertThat(status).isEqualTo(200);
        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT actor_type, actor_id, reason FROM audit.audit_entry WHERE action = 'CANDIDATE_REJECTED' AND resource = ?",
                candidate.toString());
        assertThat(entry).containsEntry("actor_type", "REVIEWER").containsEntry("actor_id", "reviewer-sub-10")
                .containsEntry("reason", "different person");
    }

    @Test
    void validTokenWithoutARoleIsRefusedAndAuditedAsAuthenticated() {
        String subject = "no-role-" + UUID.randomUUID();
        assertThat(get(TestHttp.as(TestTokens.staffWithRoles(subject, List.of())), "/api/audit/head"))
                .isEqualTo(403);
        assertRefusalAudited("API_FORBIDDEN", "AUTHENTICATED", subject, "GET /api/audit/head");
    }

    @Test
    void xRolesHeaderDoesNotTurnAnOfficerIntoAReviewer() {
        UUID candidate = pendingCandidate();
        int status = TestHttp.as(TestTokens.officer("officer-spoof"))
                .post()
                .uri(url("/api/identity/candidates/" + candidate + "/confirm"))
                .header("X-Roles", "IDENTITY_REVIEWER,REVIEWER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reviewerId", "reviewer-sub-9", "note", "trust me"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM identity_candidate_match WHERE id = ?", String.class, candidate))
                .isEqualTo("PENDING");
        assertRefusalAudited("API_FORBIDDEN", "OFFICER", "officer-spoof", "POST /api/identity/candidates/{id}/confirm");
    }

    @Test
    void actuatorEndpointsAreNotServed() {
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/metrics", "/actuator/env")) {
            int status = TestHttp.anonymous().get().uri(url(path)).exchange((rq, rs) -> rs.getStatusCode().value());
            assertThat(status).as(path).isEqualTo(404);
        }
    }

    @Test
    void anonymousCallIs401ProblemDetailAndNotChained() {
        UUID candidate = pendingCandidate();
        ResponseEntity<String> res = TestHttp.anonymous()
                .post()
                .uri(url("/api/identity/candidates/" + candidate + "/reject"))
                .header("X-Roles", "IDENTITY_REVIEWER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reviewerId", "reviewer-sub-9"))
                .exchange((rq, rs) -> ResponseEntity.status(rs.getStatusCode())
                        .headers(rs.getHeaders())
                        .body(new String(rs.getBody().readAllBytes())));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(res.getBody())
                .contains("\"status\":401")
                .contains("\"reason\":\"UNAUTHENTICATED\"")
                .contains("https://samanvay.dev/problems/security/unauthenticated");
        // counted and logged, not chained (RefusalRecordingIT)
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_entry WHERE action = 'API_UNAUTHENTICATED'", Integer.class))
                .isZero();
    }

    @Test
    void consentGrantProofIsTheTokenJtiNotAHeaderOrStubDefault() {
        String subject = "cit-grant-" + UUID.randomUUID();
        String token = TestTokens.citizen(subject);
        RestClient http = TestHttp.as(token);
        UUID citizen = http.post().uri(url("/api/identity/citizens")).contentType(MediaType.APPLICATION_JSON)
                .body(draftJson()).retrieve().body(UUID.class);
        Map<?, ?> request = http.post().uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "purposeCode", "SCHOLARSHIP_ELIGIBILITY"))
                .retrieve().body(Map.class);

        // No token: the old "stub-session" default is gone.
        int anonymous = TestHttp.anonymous().post().uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(anonymous).isEqualTo(401);

        ConsentArtifact artifact = http.post().uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .header("X-Auth-Jti", "stub-session")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen))
                .retrieve().body(ConsentArtifact.class);
        assertThat(artifact.citizenAuthRef()).isEqualTo(TestTokens.jti(token)).isNotEqualTo("stub-session");
    }

    @Test
    void citizenCannotActOnAnotherCitizensRecord() {
        UUID victim = profiles.registerSelf(draft(), "cit-victim-" + UUID.randomUUID());
        RestClient attacker = TestHttp.as(TestTokens.citizen("cit-attacker-" + UUID.randomUUID()));
        assertThat(get(attacker, "/api/consent/citizens/" + victim)).isEqualTo(403);
        assertThat(get(attacker, "/api/identity/citizens/" + victim + "/links")).isEqualTo(403);
        assertThat(get(attacker, "/api/applications?citizenId=" + victim)).isEqualTo(403);
        assertThat(get(attacker, "/api/applications")).isEqualTo(403);
        assertThat(post(attacker, "/api/journeys/POST_MATRIC_SCHOLARSHIP/start", Map.of("citizenId", victim, "submission", Map.of())))
                .isEqualTo(403);
        assertThat(post(attacker, "/api/identity/links", Map.of(
                        "citizenId", victim, "departmentCode", "REVENUE", "localIdType", "RATION",
                        "localId", "X-" + victim, "provider", "LOCAL_ID_OTP", "proof", "000000")))
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE citizen_id = ?", Integer.class, victim))
                .isZero();
    }

    @Test
    void invalidTokensAre401AndRealmsCannotBorrowEachOthersRoles() {
        String adminRoute = "/api/catalog/departments";
        assertThat(post(TestHttp.as(TestTokens.forgedOfficer("o")), "/api/journeys/instances/" + UUID.randomUUID() + "/retry", Map.of()))
                .isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.expiredOfficer("o")), "/api/journeys/exceptions")).isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.unknownIssuerOfficer("o")), "/api/journeys/exceptions")).isEqualTo(401);
        assertThat(get(TestHttp.as("not-a-jwt"), "/api/journeys/exceptions")).isEqualTo(401);
        // citizen realm cannot mint staff roles
        assertThat(post(TestHttp.as(TestTokens.citizenRealmWithRoles("c", List.of("admin", "officer"))), adminRoute, Map.of()))
                .isEqualTo(403);
        // staff realm cannot mint the citizen role
        assertThat(get(TestHttp.as(TestTokens.staffWithRoles("s", List.of("citizen"))), "/api/identity/proof-providers"))
                .isEqualTo(403);
        // a person holding the "department" realm role is still not a department client
        assertThat(post(TestHttp.as(TestTokens.staffWithRoles("s", List.of("department"))), "/api/consent/requests", Map.of()))
                .isEqualTo(403);
        // a department client is not a person
        assertThat(get(TestHttp.as(TestTokens.department("dept-x", "revenue-rest-mock")), "/api/journeys/exceptions"))
                .isEqualTo(403);
    }

    @Test
    void tokensForAnotherAudienceClientOrTypeAre401() {
        String citizenRoute = "/api/identity/proof-providers";
        String officerRoute = "/api/journeys/exceptions";
        // sanity: the realistic tokens pass
        assertThat(get(TestHttp.as(TestTokens.citizen("c-ok")), citizenRoute)).isEqualTo(200);
        assertThat(get(TestHttp.as(TestTokens.officer("o-ok")), officerRoute)).isEqualTo(200);

        assertThat(get(TestHttp.as(TestTokens.officerForOtherAudience("o")), officerRoute))
                .as("aud without samanvay-api")
                .isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.officerViaClient("o", "admin-cli")), officerRoute))
                .as("Keycloak admin-cli (password grant) token")
                .isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.officerViaClient("o", "account-console")), officerRoute))
                .as("unlisted client")
                .isEqualTo(401);
        // each realm has its own allow-list: the citizen UI client is not a staff client
        assertThat(get(TestHttp.as(TestTokens.officerViaClient("o", TestTokens.CITIZEN_UI_CLIENT)), officerRoute))
                .as("citizen client id on a staff-realm token")
                .isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.citizenWithoutAzp("c")), citizenRoute)).as("no azp").isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.citizenWithTyp("c", "ID")), citizenRoute)).as("ID token").isEqualTo(401);
        assertThat(get(TestHttp.as(TestTokens.citizenWithTyp("c", "Refresh")), citizenRoute)).as("refresh").isEqualTo(401);
    }

    @Test
    void staffRealmTokenOnCitizenRoutesIsAuthenticatedButForbidden() {
        // A valid staff token authenticates (its realm is trusted) but carries no
        // CITIZEN role - realm separation is enforced when authorizing: 403, not 401.
        RestClient officer = TestHttp.as(TestTokens.officer("o-on-citizen-route"));
        assertThat(post(officer, "/api/identity/links", Map.of())).isEqualTo(403);
        assertThat(post(officer, "/api/consent/requests/" + UUID.randomUUID() + "/grant", Map.of())).isEqualTo(403);
        assertThat(post(officer, "/api/consent/" + UUID.randomUUID() + "/revoke", Map.of())).isEqualTo(403);
        RestClient admin = TestHttp.as(TestTokens.admin("a-on-citizen-route"));
        assertThat(get(admin, "/api/identity/proof-providers")).isEqualTo(403);
    }

    @Test
    void departmentClientNeedsAScopeForEveryDataSourceOfTheJourney() {
        UUID citizen = profiles.register(draft());
        int status = post(
                TestHttp.as(TestTokens.department("dept-narrow", "revenue-rest-mock")),
                "/api/journeys/POST_MATRIC_SCHOLARSHIP/start",
                Map.of("citizenId", citizen, "submission", Map.of()));
        assertThat(status).isEqualTo(403);
        assertRefusalAudited("API_FORBIDDEN", "DEPARTMENT", "dept-narrow", "POST /api/journeys/{code}/start");
    }

    @Test
    void incompleteBodiesAre400InvalidRequestNot500() {
        RestClient admin = TestHttp.as(TestTokens.admin("admin-bad-body"));
        for (String path : List.of("/api/catalog/departments", "/api/catalog/connectors", "/api/catalog/mappings",
                "/api/catalog/import/openapi")) {
            assertThat(post(admin, path, Map.of())).as(path).isEqualTo(400);
        }
        Map<String, Object> problem = TestHttp.as(TestTokens.officer("officer-bad-body")).post()
                .uri(url("/api/identity/citizens")).contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .exchange((rq, rs) -> {
                    assertThat(rs.getStatusCode().value()).isEqualTo(400);
                    return rs.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
                });
        assertThat(problem).containsEntry("reason", "INVALID_REQUEST");
    }

    @Test
    void staticPagesStayPublic() {
        for (String page : List.of("/", "/index.html", "/audit.html", "/scholarship/", "/console.js", "/shared/auth.js")) {
            assertThat(get(TestHttp.anonymous(), page)).as(page).isEqualTo(200);
        }
    }

    private void assertRefusalAudited(String action, String actorType, String actorId, String route) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_type, actor_id, outcome FROM audit.audit_entry WHERE action = ? AND resource = ? AND actor_id = ? ORDER BY seq DESC",
                action,
                route,
                actorId);
        String path = route;
        assertThat(rows).as("audit entry for refused " + path).isNotEmpty();
        assertThat(rows.getFirst().get("actor_type")).isEqualTo(actorType);
        assertThat(rows.getFirst().get("actor_id")).isEqualTo(actorId);
        assertThat(rows.getFirst().get("outcome")).isEqualTo("DENIED");
    }

    private UUID pendingCandidate() {
        UUID citizen = profiles.register(draft());
        UUID candidate = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_candidate_match (id, citizen_id, department_code, score, features, status) "
                        + "VALUES (?, ?, 'REVENUE', 0.910, '{\"score\":0.91}'::jsonb, 'PENDING')",
                candidate,
                citizen);
        return candidate;
    }

    private int get(RestClient http, String path) {
        return http.get().uri(url(path)).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private int post(RestClient http, String path, Object body) {
        return http.post()
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static ProfileDraft draft() {
        return new ProfileDraft("Sec Citizen", "सेक", "Sec", "Citizen", "Father", LocalDate.of(2002, 2, 2), "DAY", "M", "97****00");
    }

    private static String draftJson() {
        return """
                {"nameLatin":"Sec Citizen","givenName":"Sec","familyName":"Citizen","fatherName":"Father",
                 "dob":"2002-02-02","dobPrecision":"DAY","gender":"M","contactMasked":"97****00"}
                """;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
