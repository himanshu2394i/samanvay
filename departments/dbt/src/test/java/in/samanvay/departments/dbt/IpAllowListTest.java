package in.samanvay.departments.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** DBT also restricts callers by IP: an address that is not allow-listed is refused before any token is looked at. */
class IpAllowListTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static int status(int port, String path, String method) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        b = "POST".equals(method) ? b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=x&client_secret=y")) : b.GET();
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "dbt.allowed-ips=10.9.9.9")
    class NotAllowListed {
        @LocalServerPort
        int port;

        @Test
        void the_api_and_the_token_endpoint_are_forbidden_but_the_manifest_stays_public() throws Exception {
            assertThat(status(port, "/v1/bank", "POST")).isEqualTo(403);
            assertThat(status(port, "/oauth/token", "POST")).isEqualTo(403);
            assertThat(status(port, "/.well-known/samanvay/manifest", "GET")).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "dbt.allowed-ips=127.0.0.1")
    class AllowListed {
        @LocalServerPort
        int port;

        @Test
        void an_allow_listed_address_reaches_the_normal_checks() throws Exception {
            assertThat(status(port, "/v1/bank", "POST")).isEqualTo(401); // no bearer: refused by the token check, not the IP check
            assertThat(status(port, "/oauth/token", "POST")).isEqualTo(401); // wrong client: refused by the client check
        }
    }
}
