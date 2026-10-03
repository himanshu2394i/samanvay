package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Some departments want the person ID in a JSON body, not in the URL (access logs, proxies, history). A connector says so:
 * {@code method: POST} and {@code body_inputs}. Everything else about the call (auth, path inputs) still applies.
 */
class RestAdapterPostTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    record Seen(String method, String rawUri, Map<String, String> headers, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                    h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    RestAdapter adapter(Map<String, String> secrets) {
        SecretStore store = k -> secrets.containsKey(k) ? new SecretStore.Secret(secrets.get(k).getBytes(StandardCharsets.UTF_8)) : null;
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new RestAdapter(new MockDepartmentBackend(), http, "http", DepartmentServiceOverrides.NONE, new RestAuth(new SourceCredentials(store), http, "http"));
    }

    AdapterRequest post(String endpoint, Map<String, String> inputs, Map<String, String> access, String authType, String spec) {
        return new AdapterRequest("dbt", "REST", "127.0.0.1:" + server.getAddress().getPort(), endpoint, null, inputs, "secret:dbt", authType, spec, access);
    }

    @Test
    void a_post_connector_sends_the_declared_inputs_as_a_json_body_and_none_in_the_url() {
        AdapterResponse r = adapter(Map.of()).execute(post("/v1/bank", Map.of("dbtId", "DBT-1001"), Map.of("method", "POST", "body_inputs", "dbtId"), "NONE", null));
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.rawUri()).isEqualTo("/v1/bank");
        assertThat(s.headers().get("content-type")).startsWith("application/json");
        assertThat(JSON.readTree(s.body())).isEqualTo(JSON.readTree("{\"dbtId\":\"DBT-1001\"}"));
        assertThat(r.body().get("ok").asBoolean()).isTrue();
    }

    @Test
    void values_are_json_escaped_so_a_quote_or_unicode_cannot_break_out_of_the_body() {
        String tricky = "a\"b\\c\nहि";
        adapter(Map.of()).execute(post("/v1/bank", Map.of("dbtId", tricky), Map.of("method", "POST", "body_inputs", "dbtId"), "NONE", null));
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("dbtId").asString()).isEqualTo(tricky);
        assertThat(body.size()).isEqualTo(1);
    }

    @Test
    void inputs_not_listed_as_body_inputs_go_in_the_query_and_path_inputs_are_still_substituted() {
        adapter(Map.of()).execute(post("/v1/persons/{region}/bank", Map.of("dbtId", "D1", "region", "mh", "lang", "mr"),
                Map.of("method", "POST", "body_inputs", "dbtId"), "NONE", null));
        Seen s = seen.get(0);
        assertThat(s.rawUri()).isEqualTo("/v1/persons/mh/bank?lang=mr");
        assertThat(JSON.readTree(s.body())).isEqualTo(JSON.readTree("{\"dbtId\":\"D1\"}"));
    }

    @Test
    void authentication_applies_to_a_post_exactly_as_to_a_get() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\",\"secret\":true}]}";
        adapter(Map.of("source-dbt-credential", "{\"X-Api-Key\":\"k-1\"}"))
                .execute(post("/v1/bank", Map.of("dbtId", "D1"), Map.of("method", "POST", "body_inputs", "dbtId"), "API_KEY", spec));
        assertThat(seen.get(0).headers()).containsEntry("x-api-key", "k-1");
        assertThat(seen.get(0).method()).isEqualTo("POST");
    }

    @Test
    void a_post_with_no_body_inputs_sends_an_empty_json_object() {
        adapter(Map.of()).execute(post("/v1/ping", Map.of(), Map.of("method", "POST"), "NONE", null));
        assertThat(JSON.readTree(seen.get(0).body()).size()).isZero();
        assertThat(seen.get(0).headers().get("content-type")).startsWith("application/json");
    }

    @Test
    void without_a_method_the_call_is_the_original_get_with_the_inputs_in_the_query() {
        adapter(Map.of()).execute(post("/v1/bank", Map.of("dbtId", "D1"), Map.of(), "NONE", null));
        assertThat(seen.get(0).method()).isEqualTo("GET");
        assertThat(seen.get(0).rawUri()).isEqualTo("/v1/bank?dbtId=D1");
    }

    @Test
    void only_get_and_post_are_supported_anything_else_is_refused_before_a_call_is_made() {
        for (String bad : new String[] {"DELETE", "PUT", "PATCH", "get;rm", ""}) {
            if (bad.isEmpty()) {
                continue; // blank = default GET
            }
            assertThatThrownBy(() -> adapter(Map.of()).execute(post("/v1/bank", Map.of(), Map.of("method", bad), "NONE", null)))
                    .as(bad).isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("method");
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void the_method_is_case_insensitive() {
        adapter(Map.of()).execute(post("/v1/bank", Map.of("dbtId", "D1"), Map.of("method", "post", "body_inputs", "dbtId"), "NONE", null));
        assertThat(seen.get(0).method()).isEqualTo("POST");
    }
}
