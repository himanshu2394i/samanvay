package com.samanvay.catalog.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishing a connector is a human decision backed by evidence the SERVER holds: a successful durable trial against the department
 * within the last 24 hours. What the client says in the request body ({@code passed}) is ignored, and the config "test" step now
 * really calls the department with its sample person, so test-then-publish still works as one flow.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishRequiresTrialIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String DEPT = "PUBGATE";
    static HttpServer department;

    static {
        try {
            department = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            department.createContext("/v1/bank", ex -> {
                String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
                boolean found = q.contains("dbtId=DBT-1001");
                byte[] out = (found ? "{\"accountRef\":\"XXXXXX1234\",\"holderName\":\"Asha Patil\"}" : "{}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(found ? 200 : 404, out.length);
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
        r.add("samanvay.sources.department-service.urls.pubgate-rest", () -> "http://127.0.0.1:" + department.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    CatalogOnboarding wizard;

    @Autowired
    JdbcClient jdbc;

    static int n;

    @BeforeAll
    void department() {
        wizard.registerDepartment(new DepartmentDraft(DEPT, "Publish gate dept", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft("pubgate-rest", DEPT, "REST", "pubgate.example.gov", "NONE", "secret:none"));
    }

    @AfterAll
    static void stop() {
        department.stop(0);
    }

    /** A fresh DRAFT connector, with or without a sample person. */
    String draft(boolean withSample) {
        String id = "pubgate-bank-" + (++n) + "-" + System.nanoTime();
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/bank\"" + (withSample ? ",\"sample_person_id\":\"DBT-1001\"" : "") + "}}";
        return wizard.createDraft(new ConnectorDraft(id, "pubgate-rest", DataCategory.of("BANK_ACCOUNT"), caps,
                "[{\"name\":\"dbtId\",\"from\":\"link.personId\",\"required\":true}]", 1000)).ref();
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + TestTokens.admin("admin-pubgate"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> publish(String ref, String body) throws Exception {
        return post("/api/catalog/connectors/" + ref + "/publish", body);
    }

    void trialRow(String ref, String outcome, Instant at) {
        jdbc.sql("INSERT INTO connector_trial (connector_ref, tried_at, outcome) VALUES (:r, :t, :o)"
                + " ON CONFLICT (connector_ref) DO UPDATE SET tried_at = EXCLUDED.tried_at, outcome = EXCLUDED.outcome")
                .param("r", ref).param("t", Timestamp.from(at)).param("o", outcome).update();
    }

    @Test
    void publishing_without_a_trial_is_refused_whatever_the_client_claims() throws Exception {
        String ref = draft(true);

        HttpResponse<String> r = publish(ref, "{\"passed\":true,\"failures\":[]}");

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("detail").asString()).contains("trial");
        assertThat(status(ref)).isEqualTo("DRAFT");
    }

    @Test
    void a_failed_or_stale_trial_does_not_count() throws Exception {
        String failed = draft(true);
        trialRow(failed, "NOT_FOUND", Instant.now());
        assertThat(publish(failed, "{\"passed\":true}").statusCode()).isEqualTo(409);

        String stale = draft(true);
        trialRow(stale, "SUCCESS", Instant.now().minus(25, ChronoUnit.HOURS));
        assertThat(publish(stale, "{\"passed\":true}").statusCode()).isEqualTo(409);
        assertThat(status(stale)).isEqualTo("DRAFT");
    }

    @Test
    void a_successful_trial_within_a_day_lets_it_publish_and_the_client_flag_is_not_consulted() throws Exception {
        String ref = draft(true);
        trialRow(ref, "SUCCESS", Instant.now().minus(23, ChronoUnit.HOURS));

        HttpResponse<String> r = publish(ref, "{\"passed\":false,\"failures\":[\"client says no\"]}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void the_test_step_calls_the_department_with_its_sample_person_so_test_then_publish_still_works() throws Exception {
        String ref = draft(true);

        JsonNode report = JSON.readTree(post("/api/catalog/connectors/" + ref + "/test", "").body());
        assertThat(report.get("passed").asBoolean()).isTrue();

        assertThat(publish(ref, "{\"passed\":true}").statusCode()).isEqualTo(200);
    }

    @Test
    void a_department_that_does_not_answer_for_its_own_sample_fails_the_test_step_and_so_blocks_publishing() throws Exception {
        String id = "pubgate-badsample-" + System.nanoTime();
        String ref = wizard.createDraft(new ConnectorDraft(id, "pubgate-rest", DataCategory.of("BANK_ACCOUNT"),
                "{\"FETCH\":{\"endpoint\":\"/v1/bank\",\"sample_person_id\":\"DBT-0000\"}}", "[{\"name\":\"dbtId\",\"from\":\"link.personId\",\"required\":true}]", 1000)).ref();

        JsonNode report = JSON.readTree(post("/api/catalog/connectors/" + ref + "/test", "").body());

        assertThat(report.get("passed").asBoolean()).isFalse();
        assertThat(report.get("failures").toString()).contains("trial");
        assertThat(publish(ref, "{\"passed\":true}").statusCode()).isEqualTo(409);
    }

    String status(String ref) {
        return jdbc.sql("SELECT status FROM catalog_connector WHERE ref = :r").param("r", ref).query(String.class).single();
    }
}
