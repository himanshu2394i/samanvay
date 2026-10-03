package com.samanvay.connector.internal.web;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An admin runs a trial fetch of a drafted connector for the department's fake sample person, over real HTTP with real tokens. */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorTrialEndpointIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String DEPT = "TRIALDEPT";
    static HttpServer department;

    static {
        try {
            department = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            department.createContext("/v1/bank", ex -> {
                String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
                int status = q.contains("dbtId=DBT-1001") ? 200 : q.contains("dbtId=BOOM") ? 500 : 404;
                byte[] out = (status == 200 ? "{\"accountRef\":\"XXXXXX1234\",\"holderName\":\"Asha Patil\"}" : "{}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, out.length);
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
        // a test runtime may point a REST source at a plain-HTTP stand-in
        r.add("samanvay.sources.department-service.urls.trialdept-rest", () -> "http://127.0.0.1:" + department.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    CatalogOnboarding wizard;

    String withSample;
    String withoutSample;

    @BeforeAll
    void connectors() {
        wizard.registerDepartment(new DepartmentDraft(DEPT, "Trial Dept", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft("trialdept-rest", DEPT, "REST", "trial.example.gov", "NONE", "secret:none"));
        String inputs = "[{\"name\":\"dbtId\",\"from\":\"link.personId\",\"required\":true}]";
        withSample = wizard.createDraft(new ConnectorDraft("trialdept-bank", "trialdept-rest", DataCategory.of("BANK_ACCOUNT"),
                "{\"FETCH\":{\"endpoint\":\"/v1/bank\",\"sample_person_id\":\"DBT-1001\"}}", inputs, 1000)).ref();
        withoutSample = wizard.createDraft(new ConnectorDraft("trialdept-nosample", "trialdept-rest", DataCategory.of("BANK_ACCOUNT"),
                "{\"FETCH\":{\"endpoint\":\"/v1/bank\"}}", inputs, 1000)).ref();
    }

    @AfterAll
    static void stop() {
        department.stop(0);
    }

    HttpResponse<String> trial(String ref, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/connector/trial/" + ref))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String admin() {
        return TestTokens.admin("admin-trial");
    }

    @Test
    void the_departments_sample_person_is_used_by_default_and_the_real_fields_come_back() throws Exception {
        HttpResponse<String> r = trial(withSample, null, admin());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(body.get("personId").asString()).isEqualTo("DBT-1001");
        assertThat(body.get("fields").get("accountRef").asString()).isEqualTo("XXXXXX1234");
    }

    @Test
    void an_admin_can_name_a_different_person_and_a_missing_one_is_a_clear_failure_not_a_crash() throws Exception {
        JsonNode body = JSON.readTree(trial(withSample, "{\"personId\":\"NOBODY\"}", admin()).body());
        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("outcome").asString()).isEqualTo("ERROR");
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(body.get("personId").asString()).isEqualTo("NOBODY");
    }

    @Test
    void a_department_server_error_is_reported_as_the_trials_result() throws Exception {
        JsonNode body = JSON.readTree(trial(withSample, "{\"personId\":\"BOOM\"}", admin()).body());
        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("outcome").asString()).isEqualTo("ERROR");
    }

    @Test
    void with_neither_a_sample_nor_a_named_person_it_asks_for_one() throws Exception {
        assertThat(trial(withoutSample, null, admin()).statusCode()).isEqualTo(400);
        assertThat(trial(withoutSample, "{\"personId\":\"  \"}", admin()).statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(trial(withoutSample, "{\"personId\":\"DBT-1001\"}", admin()).body()).get("ok").asBoolean()).isTrue();
    }

    @Test
    void an_unknown_connector_is_not_found() throws Exception {
        assertThat(trial("no-such-connector@1", null, admin()).statusCode()).isEqualTo(404);
    }

    @Test
    void only_an_admin_may_run_a_trial() throws Exception {
        assertThat(trial(withSample, null, null).statusCode()).isEqualTo(401);
        assertThat(trial(withSample, null, TestTokens.officer("o-1")).statusCode()).isEqualTo(403);
        assertThat(trial(withSample, null, TestTokens.citizen("c-1")).statusCode()).isEqualTo(403);
        assertThat(trial(withSample, null, TestTokens.reviewer("r-1")).statusCode()).isEqualTo(403);
    }
}
