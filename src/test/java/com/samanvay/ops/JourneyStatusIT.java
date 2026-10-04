package com.samanvay.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code GET /api/ops/journeys/{code}} over a real database and real tokens: a journey that really runs shows its
 * recent application and middle-layer log, and a draft journey shows which category is connected and working.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JourneyStatusIT extends PostgresIntegrationTest {

    static final String RUNNING_JOURNEY = "POST_MATRIC_SCHOLARSHIP";
    static final String SUFFIX = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    static final String DEPT = "OPSDEPT" + SUFFIX;
    static final String SOURCE = "opsdept-rest-" + SUFFIX.toLowerCase();
    static final String DRAFT_JOURNEY = "OPS_IT_" + SUFFIX;
    static HttpServer department;

    static {
        try {
            department = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            department.createContext("/v1/bank", ex -> {
                byte[] out = "{\"accountRef\":\"XXXXXX1234\",\"holderName\":\"Asha Patil\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            department.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry r) {
        r.add("samanvay.sources.department-service.urls." + SOURCE, () -> "http://127.0.0.1:" + department.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    CatalogOnboarding wizard;

    @Autowired
    JourneyWrite journeyWrite;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    String connectorRef;

    @BeforeAll
    void draftJourneyWithOneConnectedCategory() {
        wizard.registerDepartment(new DepartmentDraft(DEPT, "Ops IT Dept", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft(SOURCE, DEPT, "REST", "opsdept.example.gov", "NONE", "secret:none"));
        String inputs = "[{\"name\":\"dbtId\",\"from\":\"link.personId\",\"required\":true}]";
        connectorRef = wizard.createDraft(new ConnectorDraft("opsdept-bank-" + SUFFIX.toLowerCase(), SOURCE, DataCategory.of("BANK_ACCOUNT"),
                "{\"FETCH\":{\"endpoint\":\"/v1/bank\",\"sample_person_id\":\"DBT-1001\"}}", inputs, 1000)).ref();
        wizard.publish(connectorRef, new ConnectorTestReport(true, List.of()));
        journeyWrite.createJourney(new JourneyDraft(DRAFT_JOURNEY, "Ops IT scheme", "OPS", 48, "SCHOLARSHIP_ELIGIBILITY", DEPT,
                List.of("BANK_ACCOUNT", "OPS_UNCONNECTED"), Map.of("BANK_ACCOUNT", DEPT, "OPS_UNCONNECTED", DEPT),
                "https://opsdept.example.gov/apply"));
    }

    @AfterAll
    static void stop() {
        department.stop(0);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> status(String code) {
        return TestHttp.as(TestTokens.officer("ops-journey-officer")).get().uri(url("/api/ops/journeys/" + code)).retrieve().body(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDraftJourneyShowsWhichCategoryIsConnectedAndWorking() {
        Map<String, Object> body = status(DRAFT_JOURNEY);

        assertThat(body).containsEntry("code", DRAFT_JOURNEY).containsEntry("name", "Ops IT scheme").containsEntry("status", "DRAFT")
                .containsEntry("requester", DEPT).containsEntry("portalUrl", "https://opsdept.example.gov/apply");
        List<Map<String, Object>> categories = (List<Map<String, Object>>) body.get("categories");
        assertThat(categories).hasSize(2);
        Map<String, Object> connected = categories.get(0);
        assertThat(connected).containsEntry("category", "BANK_ACCOUNT").containsEntry("department", DEPT)
                .containsEntry("connectorRef", connectorRef).containsEntry("connectorStatus", "PUBLISHED")
                .containsEntry("dataSourceCode", SOURCE).containsEntry("sourceHealth", "UNKNOWN").containsEntry("working", true);
        assertThat(connected.get("lastTrial")).isNull();
        Map<String, Object> unconnected = categories.get(1);
        assertThat(unconnected).containsEntry("category", "OPS_UNCONNECTED").containsEntry("connectorStatus", "NONE")
                .containsEntry("working", false);
        assertThat(unconnected.get("connectorRef")).isNull();
        assertThat((Map<String, Object>) body.get("counts")).containsEntry("running", 0).containsEntry("last7Days", 0);
        assertThat((List<?>) body.get("recent")).isEmpty();
        assertThat((List<?>) body.get("log")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTrialRunByAnAdminShowsAsTheConnectorsLastTrial() {
        TestHttp.as(TestTokens.admin("ops-journey-admin")).post().uri(url("/api/connector/trial/" + connectorRef))
                .contentType(MediaType.APPLICATION_JSON).retrieve().toBodilessEntity();

        Map<String, Object> bank = ((List<Map<String, Object>>) status(DRAFT_JOURNEY).get("categories")).get(0);

        Map<String, Object> trial = (Map<String, Object>) bank.get("lastTrial");
        assertThat(trial).containsEntry("outcome", "SUCCESS");
        assertThat((String) trial.get("at")).isNotBlank();
    }

    @Test
    void anUnknownJourneyIsNotFound() {
        int code = TestHttp.as(TestTokens.officer("ops-journey-officer")).get().uri(url("/api/ops/journeys/NO_SUCH_JOURNEY"))
                .exchange((rq, rs) -> rs.getStatusCode().value());

        assertThat(code).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aJourneyThatReallyRunsShowsItsApplicationCountsAndMiddleLayerLog() {
        UUID citizen = profiles.register(new ProfileDraft("Ramesh Kumar", "रमेश", "Ramesh", "Kumar", "Suresh", LocalDate.of(2004, 1, 15), "DAY", "M", "99****21"));
        for (String[] link : new String[][] {{"SCHOLARSHIP", "SCHOLARSHIP_ID"}, {"REVENUE", "RATION"}, {"EDUCATION", "STUDENT"}, {"DBT", "DBT"}}) {
            linking.assertLink(citizen, link[0], link[1], link[0].substring(0, 2) + "-" + citizen.toString().substring(0, 8),
                    com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        }
        var request = consents.request(new ConsentRequestDraft(citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY"));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));
        int started = TestHttp.as(TestTokens.departmentOf("dept-scholarship-it", "SCHOLARSHIP")).post()
                .uri(url("/api/journeys/" + RUNNING_JOURNEY + "/start")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "submission", Map.of())).exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(started).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            Map<String, Object> body = status(RUNNING_JOURNEY);
            Map<String, Object> counts = (Map<String, Object>) body.get("counts");
            long total = ((Number) counts.get("running")).longValue() + ((Number) counts.get("completed")).longValue()
                    + ((Number) counts.get("failed")).longValue();
            assertThat(total).isGreaterThanOrEqualTo(1);
            assertThat(((Number) counts.get("last7Days")).longValue()).isGreaterThanOrEqualTo(1);
            List<Map<String, Object>> recent = (List<Map<String, Object>>) body.get("recent");
            assertThat(recent).isNotEmpty();
            assertThat(recent.getFirst()).containsKeys("instanceId", "referenceNo", "state", "startedAt");
            assertThat(recent.getFirst().get("referenceNo")).isNotNull();
            List<Map<String, Object>> log = (List<Map<String, Object>>) body.get("log");
            assertThat(log).isNotEmpty();
            assertThat(log.getFirst()).containsKeys("at", "referenceNo", "category", "department", "connector", "outcome", "latencyMs", "error");
            assertThat(body.get("categories")).asList().isNotEmpty();
            // never a citizen value: nothing in the document carries the citizen's name or id
            assertThat(body.toString()).doesNotContain("Ramesh").doesNotContain(citizen.toString());
        });
    }
}
