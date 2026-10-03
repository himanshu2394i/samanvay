package in.samanvay.departments.revenue;

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
    static final String NAME = "Revenue Department";

    @LocalServerPort
    int port;

    HttpResponse<String> get(String path, String accept) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_home_address_explains_what_this_service_is_in_plain_words() throws Exception {
        HttpResponse<String> r = get("/", "text/html");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(r.body()).contains(NAME).contains("demonstration service").contains("fake data");
        assertThat(r.body()).doesNotContain("Whitelabel");
    }

    @Test
    void the_home_page_is_accessible_and_works_in_light_and_dark_with_no_scripts_or_outside_requests() throws Exception {
        String body = get("/", "text/html").body();
        assertThat(body).contains("<html lang=\"en\">").contains("name=\"viewport\"").contains("prefers-color-scheme: dark");
        assertThat(body).doesNotContain("<script").doesNotContain("http://").doesNotContain("https://");
        assertThat(body).doesNotContain("—").doesNotContain("–");
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
        assertThat(get("/v1/income/ANY", "text/html").statusCode()).isEqualTo(401);
    }
}
