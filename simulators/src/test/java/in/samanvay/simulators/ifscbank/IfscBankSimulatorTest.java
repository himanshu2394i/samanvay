package in.samanvay.simulators.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulator's own behaviour: marker, auth, fixed outcomes, faults, and that no
 * response ever carries a fixture holder name. The CALLER's contract suite lives
 * in the main app next to the client (IfscBankSourceContract); it is not duplicated here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"simulator.faults.timeout-delay=1s"})
class IfscBankSimulatorTest {

    static final String BASIC = "Basic "
            + Base64.getEncoder().encodeToString("samanvay-sim-key:sim-secret-change-me".getBytes(StandardCharsets.UTF_8));
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void ifsc_lookup_returns_open_rbi_data_with_marker() throws Exception {
        HttpResponse<String> r = get("/sbin0000300", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("IFSC").asString()).isEqualTo("SBIN0000300");
        assertThat(body.get("BANK").asString()).isEqualTo("State Bank of India");
        assertThat(body.get("samanvay_simulator").asBoolean()).isTrue();
    }

    @Test
    void unknown_and_malformed_ifsc_are_404_not_found() throws Exception {
        for (String code : new String[] {"ABCD0123456", "abc", "SBIN1000300"}) {
            HttpResponse<String> r = get("/" + code, null);
            assertThat(r.statusCode()).as(code).isEqualTo(404);
            assertThat(r.body()).isEqualTo("\"Not Found\"");
            assertThat(r.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        }
    }

    @Test
    void bank_check_requires_basic_auth() throws Exception {
        HttpResponse<String> none = post(check("SBIN0000300", "00001000000001", "Asha Patil"), null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
        assertThat(none.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        assertThat(post(check("SBIN0000300", "00001000000001", "X"), "Basic d3Jvbmc6d3Jvbmc=", null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void fixed_outcomes_and_nothing_but_the_verdict() throws Exception {
        assertOutcome("SBIN0000300", "00001000000001", "VALID", "MATCH");
        assertOutcome("MAHB0000001", "00001000000002", "VALID", "PARTIAL");
        assertOutcome("HDFC0000001", "00001000000003", "VALID", "NO_MATCH");
        assertOutcome("SBIN0001593", "00001000000006", "VALID", "NOT_CHECKED");
        assertOutcome("BKID0000150", "00001000000004", "CLOSED", "NOT_CHECKED");
        assertOutcome("UTIB0000004", "00001000000005", "INVALID", "NOT_CHECKED");
        assertOutcome("UTIB0000004", "00001999999999", "INVALID", "NOT_CHECKED");
        assertOutcome("ABCD0123456", "00001000000001", "INVALID", "NOT_CHECKED");

        HttpResponse<String> bad = post(check("SBIN000030", "12", ""), BASIC, null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(bad.body()).get("invalidParams").findValuesAsString("name"))
                .containsExactly("ifsc", "accountNumber", "applicantName");
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
        assertThat(post(check("SBIN0000300", "00009000000500", "X"), BASIC, null).statusCode()).isEqualTo(503);
        assertThat(post(check("SAMS0000500", "00001000000001", "X"), BASIC, null).statusCode()).isEqualTo(503);
    }

    @Test
    void no_response_on_any_path_carries_a_fixture_holder_name() throws Exception {
        List<String> canaries = new ArrayList<>();
        try (InputStream in = new ClassPathResource("fixtures/ifsc-bank.json").getInputStream()) {
            JSON.readTree(in).get("accounts").forEach(a -> canaries.add(a.get("holder_name").asString()));
        }
        assertThat(canaries).hasSize(7).allSatisfy(c -> assertThat(c).startsWith("SIMULATED Canary "));

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (String account : new String[] {
            "00001000000001", "00001000000002", "00001000000003", "00001000000006", "00001000000004",
            "00001000000005", "73019586420417", "00009000000500", "00009000000422", "00009000000408"
        }) {
            for (String ifsc : new String[] {"SBIN0000300", "MAHB0000001", "HDFC0000001", "SBIN0001593", "BKID0000150", "UTIB0000004"}) {
                responses.add(post(check(ifsc, account, "Asha Patil"), BASIC, null));
            }
        }
        for (String fault : new String[] {"timeout", "server_error", "malformed"}) {
            responses.add(post(check("SBIN0000300", "00001000000001", "Asha Patil"), BASIC, fault));
        }
        for (String faultIfsc : new String[] {"SAMS0000408", "SAMS0000500", "SAMS0000422"}) {
            responses.add(post(check(faultIfsc, "73019586420417", "Asha Patil"), BASIC, null));
        }
        responses.add(post(check("SBIN0000300", "00001000000001", "Asha Patil"), null, null));
        responses.add(post(check("bad", "1", ""), BASIC, null));
        for (HttpResponse<String> r : responses) {
            assertThat(r.body()).as("account number echoed").doesNotContain("73019586420417");
            for (String canary : canaries) {
                assertThat(r.body()).as("%s %s", r.request().method(), r.statusCode()).doesNotContainIgnoringCase(canary);
                assertThat(r.headers().map().toString()).doesNotContainIgnoringCase(canary);
            }
        }
    }

    void assertOutcome(String ifsc, String account, String status, String nameMatch) throws Exception {
        HttpResponse<String> r = post(check(ifsc, account, "Asha Patil"), BASIC, null);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("accountStatus").asString()).as(account).isEqualTo(status);
        assertThat(body.get("nameMatch").asString()).as(account).isEqualTo(nameMatch);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accountStatus", "nameMatch", "samanvay_simulator");
    }

    static String check(String ifsc, String account, String name) {
        return """
                {"ifsc":"%s","accountNumber":"%s","applicantName":"%s"}
                """.formatted(ifsc, account, name);
    }

    HttpResponse<String> get(String path, String fault) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (fault != null) {
            b.header("X-Samanvay-Simulator-Fault", fault);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String body, String auth, String fault) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/bank-checks"))
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
