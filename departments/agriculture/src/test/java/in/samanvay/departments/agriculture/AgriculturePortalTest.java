package in.samanvay.departments.agriculture;

import static org.assertj.core.api.Assertions.assertThat;

import in.samanvay.departments.kit.PortalSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Agriculture's own portal: the manifest promises only journeys the portal serves, /login opened directly is the portal sign in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgriculturePortalTest {

    static final HttpClient HTTP = HttpClient.newHttpClient(); // does not follow redirects
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    PortalSession sessions;

    HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) {
            b.header("Cookie", "dept_session=" + cookie);
        }
        return HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void every_journey_the_manifest_lists_has_a_portal_page_for_it() throws Exception {
        String cookie = sessions.issue(new PortalSession.Session("AG-1001", UUID.randomUUID(), "Asha Patil"), Instant.now());
        JsonNode journeys = JSON.readTree(get("/.well-known/samanvay/manifest", null).body()).get("journeys");
        assertThat(journeys).isNotEmpty();
        for (JsonNode j : journeys) {
            HttpResponse<String> page = get("/portal-api/journeys/" + j.get("code").asString(), cookie);
            assertThat(page.statusCode()).as(j.get("code").asString()).isEqualTo(200);
            assertThat(JSON.readTree(page.body()).get("form")).isNotEmpty();
            assertThat(j.get("portalUrl").asString()).endsWith("/portal/#/journeys/" + j.get("code").asString());
        }
    }

    @Test
    void opening_login_directly_is_the_portal_sign_in_not_an_error() throws Exception {
        HttpResponse<String> r = get("/login", null);
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.headers().firstValue("Location").orElse("")).isEqualTo("/portal/#/sign-in");
        assertThat(get("/login?return_to=https://evil.example/x&state=s&nonce=n", null).statusCode()).isEqualTo(400);
    }

    @Test
    void the_portal_sign_in_accepts_the_demo_citizen_and_refuses_a_wrong_password() throws Exception {
        HttpResponse<String> bad = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/portal-api/sign-in"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mobile\":\"9000000001\",\"password\":\"nope\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(401);
        HttpResponse<String> ok = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/portal-api/sign-in"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mobile\":\"9000000001\",\"password\":\"asha-demo-pass\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).get("masked").asString()).isEqualTo("ending 0001");
    }
}
