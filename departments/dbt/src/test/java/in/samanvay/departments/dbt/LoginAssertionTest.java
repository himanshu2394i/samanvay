package in.samanvay.departments.dbt;

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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DBT's own login ends in a signed assertion that follows docs/contracts/login-assertion.md. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginAssertionTest {

    static final HttpClient HTTP = HttpClient.newHttpClient(); // does not follow redirects
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String RETURN_TO = "http://localhost:8080/identity/callback";

    @LocalServerPort
    int port;

    String base() {
        return "http://localhost:" + port;
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    HttpResponse<String> login(String user, String pass, String returnTo, String state, String nonce) throws Exception {
        String form = "mobile=" + enc(user) + "&otp=" + enc(pass) + "&return_to=" + enc(returnTo)
                + "&state=" + enc(state) + "&nonce=" + enc(nonce);
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String param(String location, String name) {
        for (String kv : URI.create(location).getRawQuery().split("&")) {
            if (kv.startsWith(name + "=")) {
                return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void manifest_identity_block_points_to_the_login_and_the_keys() throws Exception {
        JsonNode id = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/.well-known/samanvay/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("identity");
        assertThat(id.get("loginUrl").asString()).endsWith("/login");
        assertThat(id.get("jwksUrl").asString()).endsWith("/.well-known/jwks.json");
        assertThat(id.get("assertionIssuer").asString()).isEqualTo("dept:DBT");
    }

    @Test
    void the_login_page_is_shown_with_the_state_carried_in_the_form() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create(base() + "/login?return_to=" + enc(RETURN_TO) + "&state=abc123&nonce=n1")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Demo account (fake data)");
        assertThat(r.body()).contains("DBT").contains("name=\"mobile\"").contains("name=\"otp\"")
                .contains("value=\"abc123\"");
    }

    @Test
    void the_login_page_escapes_what_it_reflects() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create(base() + "/login?return_to=" + enc(RETURN_TO) + "&state=" + enc("\"><script>x</script>") + "&nonce=n")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).doesNotContain("<script>x</script>");
    }

    @Test
    void a_good_login_redirects_back_with_an_assertion_that_verifies_against_the_published_keys() throws Exception {
        HttpResponse<String> r = login("9000000001", "123456", RETURN_TO, "st-777", "nonce-9");
        assertThat(r.statusCode()).isEqualTo(303);
        String location = r.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(RETURN_TO + "?");
        assertThat(param(location, "state")).isEqualTo("st-777");

        SignedJWT jwt = SignedJWT.parse(param(location, "assertion"));
        HttpResponse<String> jwks = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/.well-known/jwks.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        ECKey key = JWKSet.parse(jwks.body()).getKeyByKeyId(jwt.getHeader().getKeyID()).toECKey();
        assertThat(key.isPrivate()).isFalse();
        assertThat(jwt.verify(new ECDSAVerifier(key))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");

        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo("dept:DBT");
        assertThat(c.getAudience()).containsExactly("samanvay");
        assertThat(c.getSubject()).isEqualTo("DBT-1001");
        assertThat(c.getStringClaim("person_id_type")).isEqualTo("DBT_ID");
        assertThat(c.getStringClaim("dept_code")).isEqualTo("DBT");
        assertThat(c.getStringClaim("nonce")).isEqualTo("nonce-9");
        assertThat(c.getStringClaim("state")).isEqualTo("st-777");
        assertThat(c.getJWTID()).isNotBlank();
        assertThat(c.getIssueTime().toInstant()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
        assertThat(c.getExpirationTime().toInstant()).isAfter(Instant.now());
        assertThat(c.getExpirationTime().getTime() - c.getIssueTime().getTime()).isLessThanOrEqualTo(300_000L);
        assertThat(c.getLongClaim("auth_time")).isPositive();
    }

    @Test
    void every_assertion_has_its_own_jti() throws Exception {
        String a = SignedJWT.parse(param(login("9000000001", "123456", RETURN_TO, "s", "n").headers().firstValue("Location").orElseThrow(), "assertion"))
                .getJWTClaimsSet().getJWTID();
        String b = SignedJWT.parse(param(login("9000000001", "123456", RETURN_TO, "s", "n").headers().firstValue("Location").orElseThrow(), "assertion"))
                .getJWTClaimsSet().getJWTID();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void a_different_user_gets_their_own_person_id() throws Exception {
        String loc = login("9000000002", "123456", RETURN_TO, "s", "n").headers().firstValue("Location").orElseThrow();
        assertThat(SignedJWT.parse(param(loc, "assertion")).getJWTClaimsSet().getSubject()).isEqualTo("DBT-1002");
    }

    @Test
    void a_wrong_password_or_unknown_user_is_refused_without_a_redirect() throws Exception {
        for (HttpResponse<String> r : new HttpResponse[] {
            login("9000000001", "000000", RETURN_TO, "s", "n"), login("9999999999", "123456", RETURN_TO, "s", "n")}) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Location")).isEmpty();
            assertThat(r.body()).doesNotContain("assertion=");
        }
    }

    @Test
    void a_return_to_that_is_not_allow_listed_never_gets_a_redirect_even_for_a_good_login() throws Exception {
        HttpResponse<String> r = login("9000000001", "123456", "https://evil.example/steal", "s", "n");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.headers().firstValue("Location")).isEmpty();
        // a look-alike prefix must not pass
        assertThat(login("9000000001", "123456", "http://localhost:8080.evil.example/x", "s", "n").statusCode()).isEqualTo(400);
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/login?return_to=" + enc("https://evil.example/") + "&state=s&nonce=n")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void state_and_nonce_are_required() throws Exception {
        assertThat(login("9000000001", "123456", RETURN_TO, "", "n").statusCode()).isEqualTo(400);
        assertThat(login("9000000001", "123456", RETURN_TO, "s", "").statusCode()).isEqualTo(400);
    }
}
