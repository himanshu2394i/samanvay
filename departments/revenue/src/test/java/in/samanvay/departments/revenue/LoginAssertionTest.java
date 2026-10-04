package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Revenue's own login: mobile number and password, then a one-time code. It ends in a signed assertion that follows
 * docs/contracts/login-assertion.md. The built-in demo accounts are used (no database configured).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginAssertionTest {

    static final HttpClient HTTP = HttpClient.newHttpClient(); // does not follow redirects
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String RETURN_TO = "http://localhost:8093/portal/callback";
    static final String ASHA = "9000000001";
    static final String ASHA_PASSWORD = "asha-demo-pass";
    static final String CODE = "123456";
    static final Pattern TICKET = Pattern.compile("name=\"ticket\" value=\"([^\"]+)\"");

    @LocalServerPort
    int port;

    String base() {
        return "http://localhost:" + port;
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    HttpResponse<String> post(String path, String form) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> password(String mobile, String pass, String returnTo, String state, String nonce) throws Exception {
        return post("/login", "mobile=" + enc(mobile) + "&password=" + enc(pass) + "&return_to=" + enc(returnTo) + "&state=" + enc(state) + "&nonce=" + enc(nonce));
    }

    HttpResponse<String> code(String ticket, String code, String returnTo, String state, String nonce) throws Exception {
        return post("/login/verify", "ticket=" + enc(ticket) + "&code=" + enc(code) + "&return_to=" + enc(returnTo) + "&state=" + enc(state) + "&nonce=" + enc(nonce));
    }

    static String ticketIn(HttpResponse<String> codePage) {
        Matcher m = TICKET.matcher(codePage.body());
        assertThat(m.find()).as("the code page carries a ticket").isTrue();
        return m.group(1);
    }

    /** Password step then code step, returning the final response. */
    HttpResponse<String> fullLogin(String mobile, String pass, String code, String state, String nonce) throws Exception {
        HttpResponse<String> step1 = password(mobile, pass, RETURN_TO, state, nonce);
        assertThat(step1.statusCode()).isEqualTo(200);
        return code(ticketIn(step1), code, RETURN_TO, state, nonce);
    }

    static String param(String location, String name) {
        for (String kv : URI.create(location).getRawQuery().split("&")) {
            if (kv.startsWith(name + "=")) {
                return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String assertionFrom(HttpResponse<String> done) {
        assertThat(done.statusCode()).isEqualTo(303);
        return param(done.headers().firstValue("Location").orElseThrow(), "assertion");
    }

    @Test
    void manifest_identity_block_points_to_the_login_and_the_keys() throws Exception {
        JsonNode id = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/.well-known/samanvay/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("identity");
        assertThat(id.get("loginUrl").asString()).endsWith("/login");
        assertThat(id.get("jwksUrl").asString()).endsWith("/.well-known/jwks.json");
        assertThat(id.get("assertionIssuer").asString()).isEqualTo("dept:REVENUE");
    }

    @Test
    void the_login_page_asks_for_a_mobile_number_and_a_password_and_carries_the_state_in_the_form() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create(base() + "/login?return_to=" + enc(RETURN_TO) + "&state=abc123&nonce=n1")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Revenue").contains("name=\"mobile\"").contains("name=\"password\"").contains("value=\"abc123\"")
                .contains("Demo account (fake data)");
        assertThat(r.body()).doesNotContain("name=\"code\"");
    }

    @Test
    void the_login_page_escapes_what_it_reflects() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create(base() + "/login?return_to=" + enc(RETURN_TO) + "&state=" + enc("\"><script>x</script>") + "&nonce=n")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).doesNotContain("<script>x</script>");
    }

    @Test
    void the_login_page_has_no_long_dashes_and_declares_light_and_dark_and_a_mobile_viewport() throws Exception {
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/login?return_to=" + enc(RETURN_TO) + "&state=s&nonce=n")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(body).doesNotContain("—").doesNotContain("–");
        assertThat(body).contains("prefers-color-scheme: dark").contains("<html lang=\"en\">").contains("viewport");
    }

    @Test
    void a_correct_mobile_and_password_lead_to_the_code_step_not_straight_to_the_assertion() throws Exception {
        HttpResponse<String> r = password(ASHA, ASHA_PASSWORD, RETURN_TO, "st", "nn");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Location")).isEmpty();
        assertThat(r.body()).doesNotContain("assertion=").contains("name=\"code\"").contains("ending 0001");
        assertThat(ticketIn(r)).isNotBlank();
    }

    @Test
    void a_wrong_password_or_unknown_mobile_is_refused_and_no_ticket_is_given() throws Exception {
        for (HttpResponse<String> r : new HttpResponse[] {
            password(ASHA, "wrong", RETURN_TO, "s", "n"), password("9999999999", ASHA_PASSWORD, RETURN_TO, "s", "n")}) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Location")).isEmpty();
            assertThat(r.body()).doesNotContain("name=\"ticket\"").doesNotContain("assertion=");
        }
    }

    @Test
    void the_right_code_redirects_back_with_an_assertion_that_verifies_against_the_published_keys() throws Exception {
        HttpResponse<String> done = fullLogin(ASHA, ASHA_PASSWORD, CODE, "st-777", "nonce-9");
        assertThat(done.statusCode()).isEqualTo(303);
        String location = done.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(RETURN_TO + "?");
        assertThat(param(location, "state")).isEqualTo("st-777");

        SignedJWT jwt = SignedJWT.parse(param(location, "assertion"));
        HttpResponse<String> jwks = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/.well-known/jwks.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        ECKey key = JWKSet.parse(jwks.body()).getKeyByKeyId(jwt.getHeader().getKeyID()).toECKey();
        assertThat(key.isPrivate()).isFalse();
        assertThat(jwt.verify(new ECDSAVerifier(key))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("name")).isEqualTo("Asha Patil");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("dob")).isEqualTo("2002-03-09");

        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo("dept:REVENUE");
        assertThat(c.getAudience()).containsExactly("samanvay");
        assertThat(c.getSubject()).isEqualTo("RV-1001");
        assertThat(c.getStringClaim("person_id_type")).isEqualTo("REVENUE_PERSON_ID");
        assertThat(c.getStringClaim("dept_code")).isEqualTo("REVENUE");
        assertThat(c.getStringClaim("nonce")).isEqualTo("nonce-9");
        assertThat(c.getStringClaim("state")).isEqualTo("st-777");
        assertThat(c.getJWTID()).isNotBlank();
        assertThat(c.getIssueTime().toInstant()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
        assertThat(c.getExpirationTime().toInstant()).isAfter(Instant.now());
        assertThat(c.getExpirationTime().getTime() - c.getIssueTime().getTime()).isLessThanOrEqualTo(300_000L);
        assertThat(c.getLongClaim("auth_time")).isPositive();
    }

    @Test
    void a_wrong_code_is_refused_without_a_redirect_and_the_citizen_can_try_again() throws Exception {
        HttpResponse<String> step1 = password(ASHA, ASHA_PASSWORD, RETURN_TO, "s", "n");
        String ticket = ticketIn(step1);
        HttpResponse<String> wrong = code(ticket, "000000", RETURN_TO, "s", "n");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.headers().firstValue("Location")).isEmpty();
        assertThat(wrong.body()).doesNotContain("assertion=").contains("name=\"code\"").contains("not correct");
        // the same ticket still works with the right code
        assertThat(code(ticket, CODE, RETURN_TO, "s", "n").statusCode()).isEqualTo(303);
    }

    @Test
    void every_assertion_has_its_own_jti() throws Exception {
        String a = SignedJWT.parse(assertionFrom(fullLogin(ASHA, ASHA_PASSWORD, CODE, "s", "n"))).getJWTClaimsSet().getJWTID();
        String b = SignedJWT.parse(assertionFrom(fullLogin(ASHA, ASHA_PASSWORD, CODE, "s", "n"))).getJWTClaimsSet().getJWTID();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void a_different_citizen_gets_their_own_person_id() throws Exception {
        String assertion = assertionFrom(fullLogin("9000000002", "ravi-demo-pass", CODE, "s", "n"));
        assertThat(SignedJWT.parse(assertion).getJWTClaimsSet().getSubject()).isEqualTo("RV-1002");
    }

    @Test
    void a_ticket_cannot_be_used_for_a_different_login_or_forged() throws Exception {
        String ticket = ticketIn(password(ASHA, ASHA_PASSWORD, RETURN_TO, "state-A", "nonce-A"));
        assertThat(code(ticket, CODE, RETURN_TO, "state-B", "nonce-A").statusCode()).isEqualTo(401);
        assertThat(code(ticket, CODE, RETURN_TO, "state-A", "nonce-B").statusCode()).isEqualTo(401);
        assertThat(code("forged.ticket", CODE, RETURN_TO, "state-A", "nonce-A").statusCode()).isEqualTo(401);
        assertThat(code("", CODE, RETURN_TO, "state-A", "nonce-A").statusCode()).isEqualTo(401);
    }

    @Test
    void a_return_to_that_is_not_allow_listed_never_gets_a_redirect_at_either_step() throws Exception {
        HttpResponse<String> r = password(ASHA, ASHA_PASSWORD, "https://evil.example/steal", "s", "n");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.headers().firstValue("Location")).isEmpty();
        assertThat(password(ASHA, ASHA_PASSWORD, "http://localhost:8093.evil.example/x", "s", "n").statusCode()).isEqualTo(400);
        String ticket = ticketIn(password(ASHA, ASHA_PASSWORD, RETURN_TO, "s", "n"));
        HttpResponse<String> stolen = code(ticket, CODE, "https://evil.example/steal", "s", "n");
        assertThat(stolen.statusCode()).isEqualTo(400);
        assertThat(stolen.headers().firstValue("Location")).isEmpty();
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/login?return_to=" + enc("https://evil.example/") + "&state=s&nonce=n")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void state_and_nonce_are_required() throws Exception {
        assertThat(password(ASHA, ASHA_PASSWORD, RETURN_TO, "", "n").statusCode()).isEqualTo(400);
        assertThat(password(ASHA, ASHA_PASSWORD, RETURN_TO, "s", "").statusCode()).isEqualTo(400);
    }
}
