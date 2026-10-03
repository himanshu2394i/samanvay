package in.samanvay.departments.education;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Education also restricts callers by IP: an address that is not allow-listed is refused before the SOAP message is read. */
class IpAllowListTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static int marks(int port) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/marks/service")).header("Content-Type", "text/xml")
                .header("SOAPAction", "GetMarks").POST(HttpRequest.BodyPublishers.ofString("<x/>")).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static int manifest(int port) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/samanvay/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "education.allowed-ips=10.9.9.9")
    class NotAllowListed {
        @LocalServerPort
        int port;

        @Test
        void the_service_is_forbidden_but_the_manifest_stays_public() throws Exception {
            assertThat(marks(port)).isEqualTo(403);
            assertThat(manifest(port)).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "education.allowed-ips=127.0.0.1")
    class AllowListed {
        @LocalServerPort
        int port;

        @Test
        void an_allow_listed_address_reaches_the_soap_checks() throws Exception {
            assertThat(marks(port)).isEqualTo(500); // a SOAP Fault for the empty message, not a 403
        }
    }
}
