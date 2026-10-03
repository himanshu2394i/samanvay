package com.samanvay.orchestration.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * The department that runs a journey starts it, and reads its applications, for its own citizens only. It needs no per-source scope:
 * consent and the citizen's links decide what Samanvay may fetch. Another department sees none of it.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepartmentJourneyIT extends PostgresIntegrationTest {

    static final String JOURNEY = "POST_MATRIC_SCHOLARSHIP";

    @LocalServerPort
    int port;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    final String owner = TestTokens.departmentOf("dept-scholarship-it", "SCHOLARSHIP"); // no data-source scopes at all
    final String other = TestTokens.departmentOf("dept-education-it", "EDUCATION");
    UUID citizen;

    @BeforeEach
    void setUp() {
        citizen = profiles.register(new ProfileDraft("Ramesh Kumar", "रमेश", "Ramesh", "Kumar", "Suresh", LocalDate.of(2004, 1, 15), "DAY", "M", "99****21"));
        linking.assertLink(citizen, "SCHOLARSHIP", "SCHOLARSHIP_ID", "SC-" + citizen.toString().substring(0, 8), com.samanvay.identity.api.AuthProof.localIdOtpDemo());
    }

    void linkEverything() {
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-" + citizen.toString().substring(0, 8), com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        linking.assertLink(citizen, "EDUCATION", "STUDENT", "STU-" + citizen.toString().substring(0, 8), com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        linking.assertLink(citizen, "DBT", "DBT", "DBT-" + citizen.toString().substring(0, 8), com.samanvay.identity.api.AuthProof.localIdOtpDemo());
    }

    void consent() {
        var request = consents.request(new ConsentRequestDraft(citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY"));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    int startAs(String token) {
        return TestHttp.as(token).post().uri(url("/api/journeys/" + JOURNEY + "/start")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "submission", Map.of())).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    int getStatus(String token, String path) {
        return TestHttp.as(token).get().uri(url(path)).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> applications(String token) {
        return TestHttp.as(token).get().uri(url("/api/applications?citizenId=" + citizen)).retrieve().body(List.class);
    }

    String awaitReference() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> apps = applications(owner);
            if (!apps.isEmpty()) {
                return (String) apps.getFirst().get("referenceNo");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("application never appeared");
    }

    @Test
    void the_department_that_runs_the_journey_starts_it_without_any_data_source_scope() {
        linkEverything();
        consent();
        assertThat(startAs(owner)).isEqualTo(200);
    }

    @Test
    void another_department_cannot_start_it() {
        linkEverything();
        consent();
        assertThat(startAs(other)).isEqualTo(403);
    }

    @Test
    void only_the_running_department_sees_the_applications() throws Exception {
        linkEverything();
        consent();
        assertThat(startAs(owner)).isEqualTo(200);
        String ref = awaitReference();
        for (String path : new String[] {"", "/steps", "/issued-records"}) {
            assertThat(getStatus(owner, "/api/applications/" + ref + path)).isEqualTo(200);
            assertThat(getStatus(other, "/api/applications/" + ref + path)).isEqualTo(404);
        }
        assertThat(applications(other)).isEmpty();
    }

    @Test
    void a_department_must_name_the_citizen_when_listing() {
        assertThat(getStatus(owner, "/api/applications")).isEqualTo(400);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readiness_shows_which_departments_are_connected_and_whether_consent_is_active() {
        Map<String, Object> before = TestHttp.as(owner).get().uri(url("/api/department/journeys/" + JOURNEY + "/readiness?citizenId=" + citizen))
                .retrieve().body(Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) before.get("departments");
        assertThat(items).isNotEmpty();
        assertThat(items).anyMatch(i -> Boolean.FALSE.equals(i.get("linked")));
        assertThat(before.get("consentActive")).isEqualTo(false);

        linkEverything();
        consent();
        Map<String, Object> after = TestHttp.as(owner).get().uri(url("/api/department/journeys/" + JOURNEY + "/readiness?citizenId=" + citizen))
                .retrieve().body(Map.class);
        assertThat((List<Map<String, Object>>) after.get("departments")).allMatch(i -> Boolean.TRUE.equals(i.get("linked")));
        assertThat(after.get("consentActive")).isEqualTo(true);
    }

    @Test
    void readiness_is_for_the_running_department_and_its_own_citizens_only() {
        assertThat(getStatus(other, "/api/department/journeys/" + JOURNEY + "/readiness?citizenId=" + citizen)).isEqualTo(403);
        UUID stranger = profiles.register(new ProfileDraft("Someone Else", null, null, null, null, LocalDate.of(1999, 1, 1), "DAY", null, null));
        assertThat(getStatus(owner, "/api/department/journeys/" + JOURNEY + "/readiness?citizenId=" + stranger)).isEqualTo(404);
    }
}
