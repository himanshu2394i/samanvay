package in.samanvay.departments.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The credential filter decides on the path the container will route, never on the raw request line: a ';' parameter, an encoded
 * semicolon or a dot segment is refused with 400, an encoded spelling of a protected path is still protected, and a path nobody listed is
 * protected by default. Requests are written to a socket exactly as shown, because an HTTP client would tidy them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    static String token(int port) throws Exception {
        java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/oauth/token")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=samanvay-dev&client_secret=dbt-dev-secret-change-me")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.body()).get("access_token").asString();
    }

    @Test
    void a_semicolon_in_a_protected_path_is_refused_with_or_without_a_token() throws Exception {
        String bearer = "Authorization: Bearer " + token(port);
        for (String target : new String[] {"/v1;x=1/bank", "/v1/..;x", "/v1%3Bx=1/bank", "/v1%3bx/bank", "/v1/bank;x=1", "/oauth;x=1/token",
                "/portal/../v1/bank", "/portal/%2e%2e/v1/bank", "/portal;x/../v1/bank"}) {
            assertThat(status(port, "POST", target)).as(target).isEqualTo(400);
            assertThat(status(port, "POST", target, bearer)).as(target + " with a token").isEqualTo(400);
        }
    }

    @Test
    void the_manifest_with_a_path_parameter_is_refused_not_served_unsigned() throws Exception {
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest;x")).isEqualTo(400);
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest%3Bx")).isEqualTo(400);
        assertThat(status(port, "GET", "/.well-known/samanvay/manifest")).isEqualTo(200);
    }

    @Test
    void an_encoded_spelling_of_a_protected_path_is_still_protected() throws Exception {
        assertThat(status(port, "POST", "/%76%31/bank")).isEqualTo(401);
        assertThat(status(port, "POST", "/%76%31/bank", "Authorization: Bearer " + token(port))).as("routes to the real handler, which wants a JSON body (415), instead of answering 401").isEqualTo(415);
    }

    @Test
    void everything_not_on_the_public_list_is_protected_by_default() throws Exception {
        assertThat(status(port, "GET", "/anything")).isEqualTo(401);
        assertThat(status(port, "POST", "/v1/bank")).isEqualTo(401);
        assertThat(status(port, "GET", "/anything", "Authorization: Bearer " + token(port))).isEqualTo(404);
    }

    @Test
    void the_public_list_still_works_without_a_token() throws Exception {
        assertThat(status(port, "GET", "/")).isEqualTo(302);
        assertThat(status(port, "GET", "/portal-api/config")).isEqualTo(200);
        assertThat(status(port, "GET", "/.well-known/jwks.json")).isEqualTo(200);
        assertThat(status(port, "GET", "/login")).isEqualTo(302);
    }
}
