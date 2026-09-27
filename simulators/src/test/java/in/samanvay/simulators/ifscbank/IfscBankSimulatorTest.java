package in.samanvay.simulators.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulator's own behaviour: marker, auth, fault triggers and outcomes.
 * The CALLER's contract suite lives in the main app next to the client
 * (com.samanvay.connector...IfscBankSourceContract); it is not duplicated here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"simulator.faults.timeout-delay=2s"})
class IfscBankSimulatorTest {

    static final String BASIC = "Basic "
            + Base64.getEncoder().encodeToString("rzp_test_samanvaysim:sim-secret-change-me".getBytes(StandardCharsets.UTF_8));
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void ifsc_lookup_returns_the_razorpay_shape_with_marker() throws Exception {
        HttpResponse<String> r = get("/sbin0000300", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("IFSC").asString()).isEqualTo("SBIN0000300");
        assertThat(body.get("BANK").asString()).isEqualTo("State Bank of India");
        assertThat(body.get("BANKCODE").asString()).isEqualTo("SBIN");
        assertThat(body.get("NEFT").asBoolean()).isTrue();
        assertThat(body.get("samanvay_simulator").asBoolean()).isTrue();
    }

    @Test
    void unknown_and_malformed_ifsc_are_404_not_found_like_live() throws Exception {
        for (String code : new String[] {"ABCD0123456", "abc", "SBIN1000300"}) {
            HttpResponse<String> r = get("/" + code, null);
            assertThat(r.statusCode()).as(code).isEqualTo(404);
            assertThat(r.body()).isEqualTo("\"Not Found\"");
            assertThat(r.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        }
    }

    @Test
    void account_validation_requires_basic_auth() throws Exception {
        HttpResponse<String> none = post(validation("SBIN0000300", "00001000000001", "ASHA SIMULATED PATIL"), null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        HttpResponse<String> wrong = post(validation("SBIN0000300", "00001000000001", "X"), "Basic d3Jvbmc6d3Jvbmc=", null);
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void deterministic_outcomes() throws Exception {
        JsonNode valid = JSON.readTree(post(validation("SBIN0000300", "00001000000001", "ASHA SIMULATED PATIL"), BASIC, null).body());
        assertThat(valid.at("/results/account_status").asString()).isEqualTo("active");
        assertThat(valid.at("/results/registered_name").asString()).isEqualTo("ASHA SIMULATED PATIL");
        assertThat(valid.get("id").asString()).isEqualTo(JSON.readTree(
                        post(validation("SBIN0000300", "00001000000001", "ASHA SIMULATED PATIL"), BASIC, null).body())
                .get("id").asString());

        JsonNode mismatch = JSON.readTree(post(validation("SBIN0000300", "00001000000001", "SOMEONE ELSE"), BASIC, null).body());
        assertThat(mismatch.at("/results/registered_name").asString()).isEqualTo("ASHA SIMULATED PATIL");

        JsonNode closed = JSON.readTree(post(validation("BKID0000150", "00001000000004", "VIKAS SIMULATED JADHAV"), BASIC, null).body());
        assertThat(closed.at("/results/account_status").asString()).isEqualTo("invalid");
        assertThat(closed.at("/status_details/reason").asString()).isEqualTo("account_closed");

        HttpResponse<String> badIfsc = post(validation("SBIN000030", "00001000000001", "ASHA SIMULATED PATIL"), BASIC, null);
        assertThat(badIfsc.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(badIfsc.body()).at("/error/field").asString()).isEqualTo("ifsc");
    }

    @Test
    void faults_by_header_and_by_fixture() throws Exception {
        assertThat(get("/SBIN0000300", "server_error").statusCode()).isEqualTo(503);
        assertThat(get("/SAMS0000500", null).statusCode()).isEqualTo(503);

        HttpResponse<String> malformed = get("/SAMS0000422", null);
        assertThat(malformed.statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> JSON.readTree(malformed.body())).isInstanceOf(Exception.class);
        assertThat(malformed.headers().firstValue("X-Samanvay-Simulator")).contains("true");

        HttpRequest slow = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/SAMS0000408"))
                .timeout(Duration.ofMillis(300))
                .build();
        assertThatThrownBy(() -> HTTP.send(slow, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);

        assertThat(post(validation("SBIN0000300", "00009000000500", "X"), BASIC, null).statusCode()).isEqualTo(503);
    }

    static String validation(String ifsc, String account, String name) {
        return """
                {"account_number":"7878780080316316","amount":100,"currency":"INR",
                 "fund_account":{"account_type":"bank_account",
                   "bank_account":{"name":"%s","ifsc":"%s","account_number":"%s"}}}
                """.formatted(name, ifsc, account);
    }

    HttpResponse<String> get(String path, String fault) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (fault != null) {
            b.header("X-Samanvay-Simulator-Fault", fault);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String body, String auth, String fault) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/fund_accounts/validations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (fault != null) {
            b.header("X-Samanvay-Simulator-Fault", fault);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
