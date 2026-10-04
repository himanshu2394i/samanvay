package in.samanvay.departments.education;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The login another department sends a citizen to: guessing passwords or codes is throttled (per mobile, per address, per ticket),
 * a ticket opens one login at most, and its pages are not cached or framed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "login.hardening.test=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class LoginHardeningTest {

    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static final Pattern TICKET = Pattern.compile("name=\"ticket\" value=\"([^\"]+)\"");

    @LocalServerPort
    int port;

    @Value("${education.login.allowed-return-uris}")
    java.util.List<String> allowed;

    HttpResponse<String> post(String path, String form) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String flow() {
        return "return_to=" + enc(allowed.getFirst().trim()) + "&state=state-1&nonce=nonce-1";
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    HttpResponse<String> password(String mobile, String password) throws Exception {
        return post("/login", "mobile=" + mobile + "&password=" + password + "&" + flow());
    }

    String ticketAfterRightPassword() throws Exception {
        HttpResponse<String> r = password("9000000001", "asha-demo-pass");
        assertThat(r.statusCode()).isEqualTo(200);
        Matcher m = TICKET.matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    HttpResponse<String> code(String ticket, String code) throws Exception {
        return post("/login/verify", "ticket=" + enc(ticket) + "&code=" + code + "&" + flow());
    }

    @Test
    void five_wrong_passwords_lock_the_mobile_even_against_the_right_password() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(password("9000000001", "wrong-" + i).statusCode()).isEqualTo(401);
        }
        assertThat(password("9000000001", "asha-demo-pass").statusCode()).isEqualTo(429);
        assertThat(password("9000000002", "ravi-demo-pass").statusCode()).as("another mobile is not locked").isEqualTo(200);
    }

    @Test
    void five_wrong_codes_kill_the_ticket_even_for_the_right_code() throws Exception {
        String ticket = ticketAfterRightPassword();
        for (int i = 0; i < 5; i++) {
            assertThat(code(ticket, "00000" + i).statusCode()).isEqualTo(401);
        }
        assertThat(code(ticket, "123456").statusCode()).isEqualTo(429);
    }

    @Test
    void a_ticket_opens_one_login_only() throws Exception {
        String ticket = ticketAfterRightPassword();
        HttpResponse<String> first = code(ticket, "123456");
        assertThat(first.statusCode()).isEqualTo(303);
        assertThat(first.headers().firstValue("Location").orElse("")).startsWith(allowed.getFirst().trim()).contains("assertion=");
        assertThat(code(ticket, "123456").statusCode()).isEqualTo(401);
    }

    @Test
    void login_pages_are_not_cached_or_framed_and_run_no_script() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login?" + flow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(r.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'").contains("frame-ancestors 'none'");
        HttpResponse<String> portal = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/portal-api/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(portal.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
}
