package in.samanvay.departments.education;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The credential filter decides on the path the container will route, never on the raw request line: a ';' parameter, an encoded
 * semicolon or a dot segment is refused with 400, an encoded spelling of a protected path is still protected, and a path nobody listed is
 * protected by default. Requests are written to a socket exactly as shown, because an HTTP client would tidy them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "education.allowed-ips=10.9.9.9")
class PathBypassTest {

    @LocalServerPort
    int port;

    /** One request exactly as written (a client library would tidy the path), answered with its status code. */
    static int status(int port, String method, String target, String... headers) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            for (String h : headers) {
                req.append(h).append("\r\n");
            }
            req.append("Content-Length: 0\r\n\r\n");
            s.getOutputStream().write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    @Test
    void a_semicolon_in_a_protected_path_is_refused_not_waved_past_the_ip_list() throws Exception {
        for (String target : new String[] {"/marks;x=1/service", "/marks/..;x", "/marks%3Bx=1/service", "/marks%3bx/service", "/marks/service;x=1",
                "/portal/../marks/service", "/portal/%2e%2e/marks/service", "/portal;x/../marks/service"}) {
            assertThat(status(port, "POST", target)).as(target).isEqualTo(400);
        }
    }

    @Test
    void the_manifest_with_a_path_parameter_is_refused_not_served_unsigned() throws Exception {
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest;x")).isEqualTo(400);
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest%3Bx")).isEqualTo(400);
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest")).isEqualTo(200);
    }

    @Test
    void an_encoded_spelling_of_the_soap_path_is_still_ip_restricted() throws Exception {
        assertThat(status(port, "POST", "/%6Darks/service")).isEqualTo(403);
    }

    @Test
    void everything_not_on_the_public_list_is_ip_restricted_by_default() throws Exception {
        assertThat(status(port, "GET", "/anything")).isEqualTo(403);
        assertThat(status(port, "POST", "/marks/service")).isEqualTo(403);
    }

    @Test
    void the_public_list_is_never_ip_restricted() throws Exception {
        assertThat(status(port, "GET", "/")).isEqualTo(302);
        assertThat(status(port, "GET", "/portal-api/config")).isEqualTo(200);
        assertThat(status(port, "GET", "/.well-known/jwks.json")).isEqualTo(200);
        assertThat(status(port, "GET", "/login")).isEqualTo(302);
    }
}
