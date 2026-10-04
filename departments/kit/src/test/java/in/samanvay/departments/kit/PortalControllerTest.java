package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The portal backend of a department, driven over real HTTP against a fake Samanvay: sign in, journeys, linking other departments,
 * consent signed by the department, submitting and tracking. The department supplies only its citizens and its login assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PortalControllerTest.App.class)
class PortalControllerTest {

    static final UUID CITIZEN = UUID.randomUUID();
    static final String BASE = "http://portal.test";
    static FakeSamanvay samanvay;
    static Path keyFile;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        CitizenDirectory directory() {
            return new CitizenDirectory() {
                @Override
                public Optional<String> authenticate(String mobile, String password) {
                    return "9000000001".equals(mobile) && "asha-pass".equals(password) ? Optional.of("EDU-1001") : Optional.empty();
                }

                @Override
                public Optional<Person> person(String personId) {
                    return Optional.of(new Person(personId, "Asha Patil", LocalDate.of(2004, 3, 9)));
                }
            };
        }

        @Bean
        HomeAssertions assertions() {
            return person -> "assertion-for-" + person.personId();
        }
    }

    @BeforeAll
    static void start() throws IOException {
        samanvay = new FakeSamanvay();
        keyFile = Files.createTempDirectory("kit").resolve("manifest-signing-key.jwk");
    }

    @AfterAll
    static void stop() {
        samanvay.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        if (samanvay == null) {
            samanvay = new FakeSamanvay();
            keyFile = Files.createTempDirectory("kit").resolve("manifest-signing-key.jwk");
        }
        r.add("portal.dept-code", () -> "EDUCATION");
        r.add("portal.name", () -> "State Board of Education");
        r.add("portal.initial", () -> "E");
        r.add("portal.accent", () -> "#9a3b12");
        r.add("portal.public-base-url", () -> BASE);
        r.add("portal.session-secret", () -> "kit-test-secret");
        r.add("portal.manifest-key-file", () -> keyFile.toString());
        r.add("portal.samanvay.base-url", () -> samanvay.url());
        r.add("portal.samanvay.token-url", () -> samanvay.url() + "/token");
        r.add("portal.samanvay.client-id", () -> "dept-education");
        r.add("portal.samanvay.client-secret", () -> "s3cret");
    }

    @LocalServerPort
    int port;

    @Autowired
    PortalSession sessions;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void routes() {
        samanvay.calls.clear();
        samanvay.on("POST", "/api/department/citizens/resolve", 200, "{\"citizenId\":\"" + CITIZEN + "\",\"created\":true}");
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    record Res(int status, String body, HttpHeaders headers) {
        Map<String, Object> json() {
            return JSON.readValue(body, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }

        List<Object> list() {
            return JSON.readValue(body, new tools.jackson.core.type.TypeReference<List<Object>>() {});
        }
    }

    interface HttpHeaders {
        Optional<String> first(String name);
    }

    Res send(String method, String path, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) {
            b.header("Cookie", PortalController.COOKIE + "=" + cookie);
        }
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), n -> r.headers().firstValue(n));
    }

    String signedIn() throws Exception {
        return sessions.issue(new PortalSession.Session("EDU-1001", CITIZEN, "Asha Patil"), java.time.Instant.now());
    }

    // --- sign in ---------------------------------------------------------------------------------------------

    @Test
    void the_portal_config_is_public_and_carries_the_department_brand() throws Exception {
        Res r = send("GET", "/portal-api/config", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json()).containsEntry("code", "EDUCATION").containsEntry("name", "State Board of Education").containsEntry("accent", "#9a3b12");
    }

    @Test
    void everything_else_needs_a_session() throws Exception {
        for (String path : new String[] {"/portal-api/me", "/portal-api/journeys", "/portal-api/journeys/DEMO_SERVICE", "/portal-api/applications",
                "/portal-api/journeys/DEMO_SERVICE/readiness", "/portal-api/journeys/DEMO_SERVICE/consent"}) {
            assertThat(send("GET", path, null, null).status()).as(path).isEqualTo(401);
        }
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", null, "{\"submission\":{}}").status()).isEqualTo(401);
        assertThat(send("GET", "/portal-api/me", "forged", null).status()).isEqualTo(401);
    }

    @Test
    void signing_in_takes_the_password_then_the_code_and_sets_a_http_only_session_cookie() throws Exception {
        assertThat(send("POST", "/portal-api/sign-in", null, "{\"mobile\":\"9000000001\",\"password\":\"wrong\"}").status()).isEqualTo(401);
        Res step1 = send("POST", "/portal-api/sign-in", null, "{\"mobile\":\"9000000001\",\"password\":\"asha-pass\"}");
        assertThat(step1.status()).isEqualTo(200);
        assertThat(step1.json().get("masked")).isEqualTo("ending 0001");
        String ticket = (String) step1.json().get("ticket");

        assertThat(send("POST", "/portal-api/verify", null, "{\"ticket\":\"" + ticket + "\",\"code\":\"000999\"}").status()).isEqualTo(401);
        assertThat(send("POST", "/portal-api/verify", null, "{\"ticket\":\"forged\",\"code\":\"123456\"}").status()).isEqualTo(401);
        Res step2 = send("POST", "/portal-api/verify", null, "{\"ticket\":\"" + ticket + "\",\"code\":\"123456\"}");

        assertThat(step2.status()).isEqualTo(200);
        String setCookie = step2.headers().first("Set-Cookie").orElseThrow();
        assertThat(setCookie).startsWith(PortalController.COOKIE + "=").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(samanvay.callsTo("/api/department/citizens/resolve")).hasSize(1);
        assertThat(samanvay.callsTo("/api/department/citizens/resolve").getFirst().body()).contains("assertion-for-EDU-1001");
        String cookie = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(send("GET", "/portal-api/me", cookie, null).json()).containsEntry("name", "Asha Patil").containsEntry("department", "EDUCATION");
    }

    // --- journeys --------------------------------------------------------------------------------------------

    @Test
    void the_journeys_are_the_ones_in_journeys_json() throws Exception {
        String cookie = signedIn();
        Res list = send("GET", "/portal-api/journeys", cookie, null);
        assertThat(list.list()).hasSize(1);
        assertThat(send("GET", "/portal-api/journeys/DEMO_SERVICE", cookie, null).json()).containsEntry("name", "Demo service");
        assertThat(send("GET", "/portal-api/journeys/NOPE", cookie, null).status()).isEqualTo(404);
    }

    @Test
    void readiness_is_asked_of_samanvay_for_the_signed_in_citizen_only() throws Exception {
        samanvay.on("GET", "/api/department/journeys/DEMO_SERVICE/readiness", 200, "{\"consentActive\":false,\"departments\":[]}");
        Res r = send("GET", "/portal-api/journeys/DEMO_SERVICE/readiness", signedIn(), null);
        assertThat(r.json()).containsEntry("consentActive", false);
        assertThat(samanvay.callsTo("/api/department/journeys/DEMO_SERVICE/readiness").getFirst().path()).endsWith("citizenId=" + CITIZEN);
    }

    @Test
    void the_departments_credential_is_fetched_once_and_used_as_the_bearer() throws Exception {
        samanvay.on("GET", "/api/department/journeys/DEMO_SERVICE/readiness", 200, "{\"consentActive\":false,\"departments\":[]}");
        send("GET", "/portal-api/journeys/DEMO_SERVICE/readiness", signedIn(), null);
        send("GET", "/portal-api/journeys/DEMO_SERVICE/readiness", signedIn(), null);
        assertThat(samanvay.calls.stream().map(FakeSamanvay.Call::authorization).distinct().toList()).hasSize(1).allMatch(a -> a.startsWith("Bearer tok-"));
    }

    // --- linking ---------------------------------------------------------------------------------------------

    @Test
    void starting_a_link_returns_the_other_departments_login_and_comes_back_to_this_portal() throws Exception {
        samanvay.on("POST", "/api/department/links/start", c -> new FakeSamanvay.Reply(200, "{\"loginUrl\":\"http://rev.test/login?state=s\"}"));
        Res r = send("POST", "/portal-api/journeys/DEMO_SERVICE/links/REVENUE", signedIn(), "{}");
        assertThat(r.json()).containsEntry("loginUrl", "http://rev.test/login?state=s");
        String sent = samanvay.callsTo("/api/department/links/start").getFirst().body();
        assertThat(sent).contains("REVENUE").contains(CITIZEN.toString()).contains(BASE + "/portal/callback?dept=REVENUE");
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/links/re%20venue", signedIn(), "{}").status()).isEqualTo(400);
    }

    @Test
    void the_callback_saves_the_link_and_sends_the_citizen_back_to_the_journey() throws Exception {
        samanvay.on("POST", "/api/department/links", 200, "{\"citizenId\":\"" + CITIZEN + "\"}");
        Res r = send("GET", "/portal/callback?dept=REVENUE&journey=DEMO_SERVICE&assertion=abc&state=s", signedIn(), null);
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.headers().first("Location").orElseThrow()).endsWith("/portal/#/journeys/DEMO_SERVICE?linked=REVENUE");
        assertThat(samanvay.callsTo("/api/department/links").getFirst().body()).contains("abc").contains("REVENUE");
    }

    @Test
    void a_merged_citizen_gets_a_fresh_session_for_the_surviving_record() throws Exception {
        UUID survivor = UUID.randomUUID();
        samanvay.on("POST", "/api/department/links", 200, "{\"citizenId\":\"" + survivor + "\"}");
        Res r = send("GET", "/portal/callback?dept=REVENUE&journey=DEMO_SERVICE&assertion=abc", signedIn(), null);
        String setCookie = r.headers().first("Set-Cookie").orElseThrow();
        String cookie = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(sessions.read(cookie, java.time.Instant.now()).orElseThrow().citizenId()).isEqualTo(survivor);
    }

    @Test
    void a_refused_link_is_reported_not_hidden_and_a_stranger_is_sent_to_sign_in() throws Exception {
        samanvay.on("POST", "/api/department/links", 409, "{\"detail\":\"already linked\"}");
        Res refused = send("GET", "/portal/callback?dept=REVENUE&journey=DEMO_SERVICE&assertion=abc", signedIn(), null);
        assertThat(refused.headers().first("Location").orElseThrow()).endsWith("?linkError=REVENUE");
        Res stranger = send("GET", "/portal/callback?dept=REVENUE&journey=DEMO_SERVICE&assertion=abc", null, null);
        assertThat(stranger.headers().first("Location").orElseThrow()).endsWith("/portal/#/sign-in");
        assertThat(send("GET", "/portal/callback?dept=RE%0D%0AVENUE&assertion=abc", signedIn(), null).status()).isEqualTo(400);
    }

    // --- consent ---------------------------------------------------------------------------------------------

    static final String WORDING = "{\"requestId\":\"REQ-1\",\"purposeCode\":\"DEMO_PURPOSE\",\"purposeText\":\"Check your income and bank account.\","
            + "\"categories\":[\"INCOME_CERTIFICATE\",\"BANK_ACCOUNT\"],\"providers\":[{\"code\":\"REVENUE\",\"name\":\"Revenue\"}],\"validityDays\":365,"
            + "\"nonce\":\"nonce-1\",\"expiresAt\":\"2026-10-04T10:10:00Z\"}";

    @Test
    void consent_shows_the_wording_without_the_nonce_and_signs_only_after_the_right_code() throws Exception {
        samanvay.on("POST", "/api/department/consents/requests", 200, WORDING);
        samanvay.on("POST", "/api/department/consents", 200, "{\"id\":\"c-1\"}");
        String cookie = signedIn();

        Res shown = send("GET", "/portal-api/journeys/DEMO_SERVICE/consent", cookie, null);
        assertThat(shown.json()).containsEntry("purposeText", "Check your income and bank account.").doesNotContainKey("nonce");

        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/consent", cookie, "{\"requestId\":\"REQ-1\",\"code\":\"000000\"}").status()).isEqualTo(401);
        assertThat(samanvay.callsTo("/api/department/consents")).isEmpty();

        Res ok = send("POST", "/portal-api/journeys/DEMO_SERVICE/consent", cookie, "{\"requestId\":\"REQ-1\",\"code\":\"123456\"}");
        assertThat(ok.status()).isEqualTo(200);
        String statement = (String) JSON.readValue(samanvay.callsTo("/api/department/consents").getFirst().body(), Map.class).get("statement");
        SignedJWT jwt = SignedJWT.parse(statement);
        ECKey key = ECKey.parse(Files.readString(keyFile));
        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getStringClaim("request_id")).isEqualTo("REQ-1");
        assertThat(c.getStringClaim("nonce")).isEqualTo("nonce-1");
        assertThat(c.getStringClaim("citizen_id")).isEqualTo(CITIZEN.toString());
        assertThat(c.getStringListClaim("categories")).containsExactly("INCOME_CERTIFICATE", "BANK_ACCOUNT");

        // the wording is used up
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/consent", cookie, "{\"requestId\":\"REQ-1\",\"code\":\"123456\"}").status()).isEqualTo(410);
    }

    @Test
    void a_consent_request_nobody_asked_for_cannot_be_confirmed() throws Exception {
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/consent", signedIn(), "{\"requestId\":\"REQ-NOPE\",\"code\":\"123456\"}").status()).isEqualTo(410);
    }

    // --- submit and track ----------------------------------------------------------------------------------

    @Test
    void submitting_checks_the_form_and_starts_the_journey_with_only_the_known_fields() throws Exception {
        samanvay.on("POST", "/api/journeys/DEMO_SERVICE/start", 200, "{\"id\":\"inst-1\"}");
        String cookie = signedIn();
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", cookie, "{\"submission\":{\"year\":\"FIRST\"}}").status()).isEqualTo(400);
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", cookie, "{\"submission\":{\"course\":\"BSc\",\"year\":\"THIRD\"}}").status()).isEqualTo(400);
        assertThat(samanvay.callsTo("/api/journeys/DEMO_SERVICE/start")).isEmpty();

        Res ok = send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", cookie, "{\"submission\":{\"course\":\"BSc\",\"year\":\"FIRST\",\"admin\":\"true\"}}");
        assertThat(ok.status()).isEqualTo(200);
        String sent = samanvay.callsTo("/api/journeys/DEMO_SERVICE/start").getFirst().body();
        assertThat(sent).contains("BSc").contains(CITIZEN.toString()).doesNotContain("admin");
    }

    @Test
    void samanvays_refusal_reaches_the_citizen_in_plain_words_and_an_outage_is_a_503() throws Exception {
        samanvay.on("POST", "/api/journeys/DEMO_SERVICE/start", 409, "{\"detail\":\"Connect your Revenue account first.\"}");
        Res refused = send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", signedIn(), "{\"submission\":{\"course\":\"BSc\"}}");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.json()).containsEntry("detail", "Connect your Revenue account first.");

        samanvay.on("POST", "/api/journeys/DEMO_SERVICE/start", 500, "{}");
        assertThat(send("POST", "/portal-api/journeys/DEMO_SERVICE/submit", signedIn(), "{\"submission\":{\"course\":\"BSc\"}}").status()).isEqualTo(503);
    }

    @Test
    void a_citizen_sees_only_their_own_applications() throws Exception {
        samanvay.on("GET", "/api/applications/MH-1", 200, "{\"referenceNo\":\"MH-1\",\"citizenId\":\"" + UUID.randomUUID() + "\"}");
        samanvay.on("GET", "/api/applications/MH-2", 200, "{\"referenceNo\":\"MH-2\",\"citizenId\":\"" + CITIZEN + "\"}");
        samanvay.on("GET", "/api/applications/MH-1/steps", 200, "[]");
        samanvay.on("GET", "/api/applications/MH-2/steps", 200, "[{\"stepCode\":\"MARKS\"}]");
        String cookie = signedIn();
        assertThat(send("GET", "/portal-api/applications/MH-1", cookie, null).status()).isEqualTo(404);
        assertThat(send("GET", "/portal-api/applications/MH-1/steps", cookie, null).status()).isEqualTo(404);
        assertThat(send("GET", "/portal-api/applications/MH-2", cookie, null).status()).isEqualTo(200);
        assertThat(send("GET", "/portal-api/applications/MH-2/steps", cookie, null).list()).hasSize(1);
    }
}
