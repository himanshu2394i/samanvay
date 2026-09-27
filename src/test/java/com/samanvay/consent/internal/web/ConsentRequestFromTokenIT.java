package com.samanvay.consent.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.sql.Array;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * POST /api/consent/requests: the requester comes from the token and the
 * purpose text and data categories from the catalog purpose - never from the
 * body. An officer or department client may only request for its own
 * department's purposes; anything else is a 403 with an audit entry.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentRequestFromTokenIT extends PostgresIntegrationTest {

    static final List<String> SCHOLARSHIP_CATEGORIES =
            List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CitizenProfiles profiles;

    @Test
    void citizenCannotSpoofRequesterPurposeTextOrCategories() {
        String subject = "cit-spoof-" + UUID.randomUUID();
        UUID citizen = profiles.registerSelf(draft(), subject);
        Map<?, ?> created = TestHttp.as(TestTokens.citizen(subject)).post().uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId", citizen,
                        "purposeCode", "SCHOLARSHIP_ELIGIBILITY",
                        "requesterId", "MALLORY-DEPT",
                        "purposeText", "harmless survey",
                        "categories", List.of("AADHAAR_FULL", "HEALTH_RECORD")))
                .retrieve().body(Map.class);

        Map<String, Object> row = row(created.get("id"));
        assertThat(row.get("requester_id")).isEqualTo("SCHOLARSHIP");
        assertThat(row.get("purpose_text")).isEqualTo(catalogText("SCHOLARSHIP_ELIGIBILITY")).isNotEqualTo("harmless survey");
        assertThat(categories(row)).containsExactlyInAnyOrderElementsOf(SCHOLARSHIP_CATEGORIES);
    }

    @Test
    void officerRequestsAsTheDepartmentInTheirToken() {
        UUID citizen = profiles.register(draft());
        Map<?, ?> created = TestHttp.as(TestTokens.officerOf("off-sch-1", "SCHOLARSHIP")).post()
                .uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "purposeCode", "SCHOLARSHIP_ELIGIBILITY", "requesterId", "INDUSTRY"))
                .retrieve().body(Map.class);
        Map<String, Object> row = row(created.get("id"));
        assertThat(row.get("requester_id")).isEqualTo("SCHOLARSHIP");
        assertThat(categories(row)).containsExactlyInAnyOrderElementsOf(SCHOLARSHIP_CATEGORIES);
    }

    @Test
    void officerOfAnotherDepartmentIsRefusedAndAudited() {
        UUID citizen = profiles.register(draft());
        String officer = "off-rev-" + UUID.randomUUID();
        int status = post(TestHttp.as(TestTokens.officerOf(officer, "REVENUE")),
                Map.of("citizenId", citizen, "purposeCode", "SCHOLARSHIP_ELIGIBILITY", "requesterId", "SCHOLARSHIP"));
        assertThat(status).isEqualTo(403);
        assertNoRequestFor(citizen);
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_type, actor_id, outcome, reason FROM audit.audit_entry WHERE action = 'API_FORBIDDEN' AND actor_id = ?",
                officer);
        assertThat(audit.get("actor_type")).isEqualTo("OFFICER");
        assertThat(audit.get("outcome")).isEqualTo("DENIED");
        assertThat(audit.get("reason")).isEqualTo("REQUESTER_NOT_ENTITLED");
    }

    @Test
    void officerWithoutDepartmentAndForeignDepartmentClientAreRefused() {
        UUID citizen = profiles.register(draft());
        Map<String, Object> body = Map.of("citizenId", citizen, "purposeCode", "SCHOLARSHIP_ELIGIBILITY");
        assertThat(post(TestHttp.as(TestTokens.officerOf("off-nodept", null)), body)).isEqualTo(403);
        assertThat(post(TestHttp.as(TestTokens.departmentOf("dept-x", "AGRICULTURE", "revenue-rest-mock")), body))
                .isEqualTo(403);
        assertNoRequestFor(citizen);
        assertThat(post(TestHttp.as(TestTokens.departmentOf("dept-x", "SCHOLARSHIP", "revenue-rest-mock")), body))
                .isEqualTo(200);
    }

    @Test
    void unknownAndRetiredPurposesAreRejected() {
        jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories) "
                + "VALUES ('RETIRED_SCH', 'retired', 'JOURNEY', 'RETIRED', 'SCHOLARSHIP', ARRAY['MARKS']) ON CONFLICT DO NOTHING");
        UUID citizen = profiles.register(draft());
        RestClient officer = TestHttp.as(TestTokens.officerOf("off-sch-2", "SCHOLARSHIP"));
        assertThat(post(officer, Map.of("citizenId", citizen, "purposeCode", "NOT_A_PURPOSE"))).isEqualTo(400);
        assertThat(post(officer, Map.of("citizenId", citizen, "purposeCode", "RETIRED_SCH"))).isEqualTo(400);
        assertNoRequestFor(citizen);
    }

    private void assertNoRequestFor(UUID citizen) {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM consent_request WHERE subject_citizen_id = ?", Integer.class, citizen))
                .isZero();
    }

    private Map<String, Object> row(Object id) {
        return jdbc.queryForMap(
                "SELECT requester_id, purpose_text, data_categories FROM consent_request WHERE id = ?::uuid", id.toString());
    }

    private String catalogText(String code) {
        return jdbc.queryForObject("SELECT text FROM catalog_purpose WHERE code = ?", String.class, code);
    }

    private static List<String> categories(Map<String, Object> row) {
        try {
            return List.of((String[]) ((Array) row.get("data_categories")).getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int post(RestClient http, Object body) {
        return http.post().uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static ProfileDraft draft() {
        return new ProfileDraft("Req Citizen", "रेक", "Req", "Citizen", "Father", LocalDate.of(2001, 3, 3), "DAY", "F", "98****01");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
