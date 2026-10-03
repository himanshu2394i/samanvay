package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** REST calls authenticate the way the department's manifest declared: API key, Basic, OAuth2 client credentials. */
class RestAdapterAuthTest {

    record Seen(String method, String rawUri, Map<String, String> headers, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int tokenStatus = 200;
    volatile String tokenJson = "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":600}";

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(record(ex));
            boolean token = ex.getRequestURI().getPath().equals("/oauth/token");
            byte[] out = (token ? tokenJson : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(token ? tokenStatus : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    static Seen record(HttpExchange ex) throws java.io.IOException {
        Map<String, String> h = new java.util.HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        return new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()), h,
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    String host() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    RestAdapter adapter(Map<String, String> secrets) {
        SecretStore store = k -> secrets.containsKey(k) ? new SecretStore.Secret(secrets.get(k).getBytes(StandardCharsets.UTF_8)) : null;
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new RestAdapter(new MockDepartmentBackend(), http, "http", DepartmentServiceOverrides.NONE,
                new RestAuth(new SourceCredentials(store), http, "http"));
    }

    AdapterRequest req(String source, String endpoint, Map<String, String> inputs, String authType, String spec) {
        return new AdapterRequest(source, "REST", host(), endpoint, null, inputs, "secret:" + source, authType, spec);
    }

    List<Seen> businessCalls() {
        List<Seen> out = new ArrayList<>();
        seen.stream().filter(s -> !s.rawUri().startsWith("/oauth/token")).forEach(out::add);
        return out;
    }

    @Test
    void no_auth_scheme_sends_no_credentials_headers() {
        adapter(Map.of()).execute(req("rev", "/v1/x", Map.of(), "NONE", null));
        assertThat(seen.get(0).headers()).doesNotContainKeys("x-api-key", "authorization");
    }

    @Test
    void an_api_key_goes_in_the_declared_header() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\",\"secret\":true}]}";
        AdapterResponse r = adapter(Map.of("source-rev-credential", "{\"X-Api-Key\":\"k-123\"}"))
                .execute(req("rev", "/v1/x", Map.of(), "API_KEY", spec));
        assertThat(seen.get(0).headers()).containsEntry("x-api-key", "k-123");
        assertThat(r.body().get("ok").asBoolean()).isTrue();
    }

    @Test
    void an_api_key_can_go_in_the_query_string_next_to_the_business_inputs() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"api_key\",\"in\":\"query\",\"secret\":true}]}";
        adapter(Map.of("source-rev-credential", "{\"api_key\":\"a b&c\"}"))
                .execute(req("rev", "/v1/x", Map.of("dbtId", "D 1"), "API_KEY", spec));
        String uri = seen.get(0).rawUri();
        assertThat(uri).contains("api_key=a+b%26c").contains("dbtId=D+1");
    }

    @Test
    void basic_auth_uses_the_username_and_password_parameters() {
        String spec = "{\"scheme\":\"BASIC\",\"parameters\":[{\"name\":\"username\",\"in\":\"header\"},{\"name\":\"password\",\"in\":\"header\",\"secret\":true}]}";
        adapter(Map.of("source-rev-credential", "svc:pw")).execute(req("rev", "/v1/x", Map.of(), "BASIC", spec));
        String expected = "Basic " + Base64.getEncoder().encodeToString("svc:pw".getBytes(StandardCharsets.UTF_8));
        assertThat(seen.get(0).headers()).containsEntry("authorization", expected);
    }

    @Test
    void oauth2_client_credentials_fetches_a_token_then_sends_it_as_a_bearer() {
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"scopes\":[\"bank.read\"],"
                + "\"parameters\":[{\"name\":\"client_id\",\"in\":\"token-request\"},{\"name\":\"client_secret\",\"in\":\"token-request\",\"secret\":true}]}";
        adapter(Map.of("source-dbt-credential", "{\"client_id\":\"samanvay-dev\",\"client_secret\":\"s3cret\"}"))
                .execute(req("dbt", "/v1/bank", Map.of("dbtId", "DBT-1001"), "OAUTH2_CLIENT", spec));

        Seen tokenCall = seen.get(0);
        assertThat(tokenCall.method()).isEqualTo("POST");
        assertThat(tokenCall.rawUri()).isEqualTo("/oauth/token");
        assertThat(tokenCall.headers().get("content-type")).startsWith("application/x-www-form-urlencoded");
        assertThat(tokenCall.body()).contains("grant_type=client_credentials").contains("client_id=samanvay-dev")
                .contains("client_secret=s3cret").contains("scope=bank.read");
        Seen call = businessCalls().get(0);
        assertThat(call.headers()).containsEntry("authorization", "Bearer tok-1");
        assertThat(call.rawUri()).isEqualTo("/v1/bank?dbtId=DBT-1001");
    }

    @Test
    void an_oauth2_token_is_cached_per_source_until_it_expires() {
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"parameters\":[]}";
        String cred = "{\"client_id\":\"a\",\"client_secret\":\"b\"}";
        RestAdapter a = adapter(Map.of("source-dbt-credential", cred, "source-other-credential", cred));
        a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec));
        a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec));
        assertThat(seen.stream().filter(s -> s.rawUri().startsWith("/oauth/token"))).hasSize(1);
        a.execute(req("other", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec));
        assertThat(seen.stream().filter(s -> s.rawUri().startsWith("/oauth/token"))).hasSize(2);
    }

    @Test
    void an_already_expired_oauth2_token_is_fetched_again_next_call() {
        tokenJson = "{\"access_token\":\"tok-short\",\"expires_in\":0}";
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"parameters\":[]}";
        RestAdapter a = adapter(Map.of("source-dbt-credential", "{\"client_id\":\"a\",\"client_secret\":\"b\"}"));
        a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec));
        a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec));
        assertThat(seen.stream().filter(s -> s.rawUri().startsWith("/oauth/token"))).hasSize(2);
    }

    @Test
    void a_refused_token_request_fails_without_calling_the_business_endpoint_or_leaking_the_secret() {
        tokenStatus = 401;
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"parameters\":[]}";
        RestAdapter a = adapter(Map.of("source-dbt-credential", "{\"client_id\":\"a\",\"client_secret\":\"TOP-SECRET\"}"));
        assertThatThrownBy(() -> a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec)))
                .isInstanceOf(RuntimeException.class).satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("TOP-SECRET"));
        assertThat(businessCalls()).isEmpty();
    }

    @Test
    void a_token_response_without_an_access_token_is_an_error() {
        tokenJson = "{\"error\":\"nope\"}";
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"parameters\":[]}";
        RestAdapter a = adapter(Map.of("source-dbt-credential", "{\"client_id\":\"a\",\"client_secret\":\"b\"}"));
        assertThatThrownBy(() -> a.execute(req("dbt", "/v1/bank", Map.of(), "OAUTH2_CLIENT", spec)))
                .isInstanceOf(IllegalConnectorConfigurationException.class);
        assertThat(businessCalls()).isEmpty();
    }

    @Test
    void a_missing_credential_names_the_source_and_parameter_never_a_value_and_makes_no_call() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\",\"secret\":true}]}";
        RestAdapter a = adapter(Map.of());
        assertThatThrownBy(() -> a.execute(req("rev", "/v1/x", Map.of(), "API_KEY", spec)))
                .isInstanceOf(IllegalConnectorConfigurationException.class)
                .hasMessageContaining("rev").hasMessageContaining("X-Api-Key");
        assertThat(seen).isEmpty();
    }

    @Test
    void a_secret_that_lacks_one_declared_parameter_is_refused() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\"},{\"name\":\"X-Client\",\"in\":\"header\"}]}";
        RestAdapter a = adapter(Map.of("source-rev-credential", "{\"X-Api-Key\":\"k\"}"));
        assertThatThrownBy(() -> a.execute(req("rev", "/v1/x", Map.of(), "API_KEY", spec)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("X-Client");
        assertThat(seen).isEmpty();
    }

    @Test
    void a_parameter_value_with_a_line_break_cannot_inject_a_header() {
        String spec = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\"}]}";
        RestAdapter a = adapter(Map.of("source-rev-credential", "{\"X-Api-Key\":\"k\\r\\nX-Evil: 1\"}"));
        assertThatThrownBy(() -> a.execute(req("rev", "/v1/x", Map.of(), "API_KEY", spec))).isInstanceOf(RuntimeException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void inputs_declared_in_the_path_are_substituted_encoded_and_the_rest_go_in_the_query() {
        adapter(Map.of()).execute(req("rev", "/v1/income/{key}", Map.of("key", "../a b", "extra", "x"), "NONE", null));
        String uri = seen.get(0).rawUri();
        assertThat(uri).startsWith("/v1/income/..%2Fa%20b").endsWith("?extra=x");
    }
}
