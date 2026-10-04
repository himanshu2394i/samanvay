package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A manifest path or endpoint is data from a department. Joined to the registered origin it must never move the call to
 * another host (".evil.com/x" would make "https://dept.example.gov.evil.com/x"; "@169.254.169.254/" would put the
 * origin in the userinfo). The adapters refuse such an endpoint before any request leaves, and nothing is ever sent.
 */
class EndpointRetargetingTest {

    static final List<String> BAD = List.of(".evil.com/x", "@169.254.169.254/", "//evil.com", "https://evil.com", "/a/../b", "/a b", "/a\\b");

    HttpServer server;
    final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(ex.getRequestURI().toString());
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

    String host() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    DeadlineHttp http() {
        return new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    AdapterRequest request(String protocol, String endpoint, String authType, String spec) {
        return new AdapterRequest("dept-src", protocol, host(), endpoint, "<id>{{id}}</id>", Map.of("id", "1"), "secret:dept-src", authType, spec, Map.of());
    }

    @Test
    void the_rest_adapter_refuses_an_endpoint_that_could_leave_the_registered_host() {
        RestAdapter adapter = new RestAdapter(new MockDepartmentBackend(), http(), "http");
        for (String bad : BAD) {
            assertThatThrownBy(() -> adapter.execute(request("REST", bad, "NONE", null))).as(bad)
                    .isInstanceOf(IllegalConnectorConfigurationException.class);
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void the_soap_adapter_refuses_an_endpoint_that_could_leave_the_registered_host() {
        SoapAdapter adapter = new SoapAdapter(new MockDepartmentBackend(), http(), "http");
        for (String bad : BAD) {
            assertThatThrownBy(() -> adapter.execute(request("SOAP", bad, "NONE", null))).as(bad)
                    .isInstanceOf(IllegalConnectorConfigurationException.class);
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void an_ordinary_endpoint_with_a_query_still_goes_to_the_registered_host() {
        RestAdapter adapter = new RestAdapter(new MockDepartmentBackend(), http(), "http");
        adapter.execute(request("REST", "/v1/persons/{id}/documents?type=INCOME_CERTIFICATE", "NONE", null));
        assertThat(seen).containsExactly("/v1/persons/1/documents?type=INCOME_CERTIFICATE");
    }

    @Test
    void an_oauth_token_url_that_could_leave_the_registered_host_is_refused_before_the_token_call() {
        String spec = "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"//evil.com/token\",\"parameters\":[{\"name\":\"client_id\",\"in\":\"token-request\",\"secret\":false},"
                + "{\"name\":\"client_secret\",\"in\":\"token-request\",\"secret\":true}]}";
        SourceCredentials creds = new SourceCredentials(k -> new com.samanvay.shared.SecretStore.Secret("{\"client_id\":\"a\",\"client_secret\":\"b\"}".getBytes(StandardCharsets.UTF_8)));
        RestAuth auth = new RestAuth(creds, http(), "http");
        assertThatThrownBy(() -> auth.apply(request("REST", "/x", "OAUTH2_CLIENT", spec), "http://" + host()))
                .isInstanceOf(IllegalConnectorConfigurationException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void a_url_whose_host_differs_from_the_origin_is_refused_by_the_origin_check() {
        assertThatThrownBy(() -> EndpointCheck.assertSameOrigin(java.net.URI.create("https://dept.example.gov.evil.com/x"), "https://dept.example.gov"))
                .isInstanceOf(IllegalConnectorConfigurationException.class);
        assertThatThrownBy(() -> EndpointCheck.assertSameOrigin(java.net.URI.create("https://dept.example.gov:8443/x"), "https://dept.example.gov"))
                .isInstanceOf(IllegalConnectorConfigurationException.class);
        EndpointCheck.assertSameOrigin(java.net.URI.create("https://DEPT.example.gov/x"), "https://dept.example.gov");
    }
}
