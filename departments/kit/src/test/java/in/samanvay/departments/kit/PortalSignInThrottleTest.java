package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Guessing passwords or one-time codes at the portal is slowed to a crawl: per mobile, per address and per ticket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PortalControllerTest.App.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PortalSignInThrottleTest {

    static FakeSamanvay samanvay;
    static Path keyFile;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void start() throws IOException {
        init();
    }

    static synchronized void init() throws IOException {
        if (samanvay == null) {
            samanvay = new FakeSamanvay();
            keyFile = Files.createTempDirectory("kit-throttle").resolve("manifest-signing-key.jwk");
            samanvay.on("POST", "/api/department/citizens/resolve", 200, "{\"citizenId\":\"" + UUID.randomUUID() + "\",\"created\":true}");
        }
    }

    @AfterAll
    static void stop() {
        samanvay.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        init();
        r.add("department.demo-mode", () -> "true");
        r.add("portal.dept-code", () -> "EDUCATION");
        r.add("portal.name", () -> "State Board of Education");
        r.add("portal.initial", () -> "E");
        r.add("portal.accent", () -> "#9a3b12");
        r.add("portal.public-base-url", () -> "http://portal.test");
        r.add("portal.session-secret", () -> "kit-throttle-secret");
        r.add("portal.manifest-key-file", () -> keyFile.toString());
        r.add("portal.samanvay.base-url", () -> samanvay.url());
        r.add("portal.samanvay.token-url", () -> samanvay.url() + "/token");
        r.add("portal.samanvay.client-id", () -> "dept-education");
        r.add("portal.samanvay.client-secret", () -> "s3cret");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    int signIn(String mobile, String password) throws Exception {
        return post("/portal-api/sign-in", "{\"mobile\":\"" + mobile + "\",\"password\":\"" + password + "\"}").statusCode();
    }

    @Test
    void five_wrong_passwords_for_one_mobile_lock_it_even_against_the_right_password() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(signIn("9000000001", "wrong-" + i)).isEqualTo(401);
        }
        assertThat(signIn("9000000001", "asha-pass")).isEqualTo(429);
        assertThat(signIn("9000000002", "wrong")).as("another mobile is not locked").isEqualTo(401);
    }

    @Test
    void many_wrong_passwords_from_one_address_across_many_mobiles_are_throttled() throws Exception {
        for (int i = 0; i < SignInThrottle.PER_IP; i++) {
            assertThat(signIn("90000001" + String.format("%02d", i), "wrong")).isEqualTo(401);
        }
        assertThat(signIn("9000000001", "asha-pass")).as("the address is locked, right password or not").isEqualTo(429);
    }

    @Test
    void five_wrong_codes_kill_the_ticket_even_for_the_right_code() throws Exception {
        String ticket = (String) JSON.readValue(post("/portal-api/sign-in", "{\"mobile\":\"9000000001\",\"password\":\"asha-pass\"}").body(), java.util.Map.class)
                .get("ticket");
        for (int i = 0; i < 5; i++) {
            assertThat(post("/portal-api/verify", "{\"ticket\":\"" + ticket + "\",\"code\":\"00000" + i + "\"}").statusCode()).isEqualTo(401);
        }
        assertThat(post("/portal-api/verify", "{\"ticket\":\"" + ticket + "\",\"code\":\"123456\"}").statusCode()).isEqualTo(429);
    }

    @Test
    void forged_tickets_count_against_the_address() throws Exception {
        for (int i = 0; i < SignInThrottle.PER_IP; i++) {
            assertThat(post("/portal-api/verify", "{\"ticket\":\"forged-" + i + "\",\"code\":\"123456\"}").statusCode()).isEqualTo(401);
        }
        assertThat(post("/portal-api/verify", "{\"ticket\":\"forged-next\",\"code\":\"123456\"}").statusCode()).isEqualTo(429);
    }
}
