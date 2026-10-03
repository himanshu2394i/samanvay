package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Revenue also restricts callers by IP: a good API key from an address that is not allow-listed is refused. */
class IpAllowListTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static int status(int port, String path, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (key != null) {
            b.header("X-Api-Key", key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "revenue.allowed-ips=10.9.9.9")
    class CallerNotAllowListed {
        @LocalServerPort
        int port;

        @Test
        void a_valid_key_from_a_non_allow_listed_address_is_forbidden() throws Exception {
            assertThat(status(port, "/v1/income/INC-2026-0007", "revenue-dev-key-change-me")).isEqualTo(403);
        }

        @Test
        void the_public_manifest_is_not_ip_restricted() throws Exception {
            assertThat(status(port, "/.well-known/samanvay/manifest", null)).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "revenue.allowed-ips=127.0.0.1,10.9.9.9")
    class CallerAllowListed {
        @LocalServerPort
        int port;

        @Test
        void a_valid_key_from_an_allow_listed_address_gets_through() throws Exception {
            assertThat(status(port, "/v1/income/INC-2026-0007", "revenue-dev-key-change-me")).isEqualTo(200);
        }

        @Test
        void an_allow_listed_address_still_needs_the_key() throws Exception {
            assertThat(status(port, "/v1/income/INC-2026-0007", null)).isEqualTo(401);
        }
    }
}
