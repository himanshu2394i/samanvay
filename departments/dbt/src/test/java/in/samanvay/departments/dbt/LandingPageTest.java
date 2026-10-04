package in.samanvay.departments.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Someone who opens the department's address in a browser sees a plain explanation, not a framework error page; a wrong address gets a
 * friendly page too. API clients that ask for JSON still get JSON, and nothing here weakens the secured endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LandingPageTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String NAME = "Direct Benefit Transfer";

    @LocalServerPort
    int port;

    HttpResponse<String> get(String path, String accept) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_home_address_is_the_citizen_portal() throws Exception {
        HttpResponse<String> r = get("/", "text/html");
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.headers().firstValue("Location").orElse("")).isEqualTo("/portal/");
    }

    @Test
    void a_wrong_address_gets_a_friendly_not_found_page_instead_of_the_framework_error() throws Exception {
        HttpResponse<String> r = get("/no-such-page", "text/html");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains(NAME).contains("does not exist");
        assertThat(r.body()).doesNotContain("Whitelabel").doesNotContain("no explicit mapping");
    }

    @Test
    void api_clients_that_ask_for_json_still_get_json_errors() throws Exception {
        HttpResponse<String> r = get("/no-such-page", "application/json");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("json");
        assertThat(r.body()).doesNotContain("<html");
    }

    @Test
    void the_secured_endpoints_are_still_secured() throws Exception {
        assertThat(get("/v1/bank", "text/html").statusCode()).isEqualTo(401);
    }
}
