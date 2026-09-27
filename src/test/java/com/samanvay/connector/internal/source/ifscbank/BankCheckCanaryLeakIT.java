package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.BankCheck;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.IfscLookup;
import com.samanvay.shared.test.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
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
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canary leak test. Each simulator fixture account has a canary holder name
 * ("SIMULATED Canary ..."). Bank-check contract v1 never returns the holder name,
 * so none of these may ever show up on the Samanvay side:
 * <ul>
 *   <li>API response bodies and problem-detail bodies: through a test-only endpoint
 *       that calls the real client bean and lets failures reach the real shared
 *       {@code ApiExceptionHandler}. It stands in for the journey endpoint, which
 *       comes in a later PR.
 *   <li>log lines: everything captured on stdout/stderr, at DEBUG for the client.
 *   <li>exception messages: message, toString, cause chain and problem properties.
 *   <li>audit rows: every row of {@code audit.audit_entry}, as JSON.
 *   <li>metric tags: every meter id in the context's and the global registries.
 *   <li>the simulator's own raw responses, faults included: it must not echo the name either.
 * </ul>
 * Paths covered: success (all six fixture verdicts), timeout, 503 and truncated
 * JSON, for both bank check and IFSC lookup.
 *
 * <p>Today nothing on this path writes audit rows or metrics. Those checks are
 * tripwires for the journey PR, which will add both.
 */
@SpringBootTest(
        classes = SamanvayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "samanvay.sources.ifsc-bank.read-timeout=1s",
            "logging.level.com.samanvay.connector=DEBUG"
        })
@Import(BankCheckCanaryLeakIT.TestOnlyBankCheckEndpoint.class)
@ExtendWith(OutputCaptureExtension.class)
class BankCheckCanaryLeakIT extends PostgresIntegrationTest {

    static final List<String> CANARIES = DepartmentSimulator.canaryHolderNames();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void simulator(DynamicPropertyRegistry registry) {
        registry.add("samanvay.sources.ifsc-bank.base-url", () -> DepartmentSimulator.baseUrl().toString());
        registry.add("samanvay.sources.ifsc-bank.key-id", () -> DepartmentSimulator.KEY_ID);
        registry.add("samanvay.sources.ifsc-bank.key-secret", () -> DepartmentSimulator.KEY_SECRET);
    }

    @LocalServerPort
    int port;

    @Autowired
    IfscBankClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectProvider<MeterRegistry> registries;

    /** Everything observed during one test, to be scanned for canaries. */
    final List<String> surfaces = new ArrayList<>();

    @BeforeEach
    void canariesAreReal() {
        assertThat(CANARIES).hasSize(6).allSatisfy(c -> assertThat(c).startsWith("SIMULATED Canary "));
    }

    @Test
    void success_paths_leak_nothing(CapturedOutput output) throws Exception {
        String[][] accounts = {
            {"SBIN0000300", "00001000000001"}, {"MAHB0000001", "00001000000002"}, {"HDFC0000001", "00001000000003"},
            {"SBIN0001593", "00001000000006"}, {"BKID0000150", "00001000000004"}, {"UTIB0000004", "00001000000005"}
        };
        for (String[] a : accounts) {
            BankCheck check = client.check(a[0], a[1], "Asha Patil");
            surfaces.add("RESULT " + check);
            surfaces.add(api("/test-only/ifsc-bank/check?ifsc=" + a[0] + "&accountNumber=" + a[1] + "&applicantName=Asha%20Patil"));
            surfaces.add(rawSimulatorCheck(a[0], a[1], null));
        }
        IfscLookup lookup = client.lookupIfsc("SBIN0000300");
        surfaces.add("RESULT " + lookup);
        surfaces.add(api("/test-only/ifsc-bank/ifsc/SBIN0000300"));
        assertNoCanary(output);
    }

    @Test
    void timeout_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000408", "SAMS0000408", "timeout");
        assertNoCanary(output);
    }

    @Test
    void server_error_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000500", "SAMS0000500", "server_error");
        assertNoCanary(output);
    }

    @Test
    void truncated_json_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000422", "SAMS0000422", "malformed");
        assertNoCanary(output);
    }

    /** One fault, driven through the bean, the API endpoint and the raw simulator, for both calls. */
    private void faultPath(String account, String ifsc, String headerFault) throws Exception {
        try {
            client.check("SBIN0000300", account, "Asha Patil");
            throw new AssertionError("expected a failure for " + account);
        } catch (IfscBankSourceException e) {
            surfaces.addAll(describe(e));
        }
        try {
            client.lookupIfsc(ifsc);
            throw new AssertionError("expected a failure for " + ifsc);
        } catch (IfscBankSourceException e) {
            surfaces.addAll(describe(e));
        }
        String problem = api("/test-only/ifsc-bank/check?ifsc=SBIN0000300&accountNumber=" + account + "&applicantName=Asha%20Patil");
        assertThat(problem).contains("\"source\":\"ifsc-bank\"");
        surfaces.add(problem);
        surfaces.add(api("/test-only/ifsc-bank/ifsc/" + ifsc));
        // The simulator's fault responses themselves, fixture-triggered and header-triggered, on every fixture account.
        surfaces.add(rawSimulatorCheck("SBIN0000300", account, null));
        for (String a : new String[] {"00001000000001", "00001000000002", "00001000000003", "00001000000006", "00001000000004", "00001000000005"}) {
            surfaces.add(rawSimulatorCheck("SBIN0000300", a, headerFault));
        }
    }

    private void assertNoCanary(CapturedOutput output) {
        // Proves the log capture sees the client's own lines (DEBUG verdicts, WARN failures).
        assertThat(output.getAll()).contains("ifsc-bank ");
        List<String> all = new ArrayList<>(surfaces);
        all.add("LOGS:\n" + output.getAll());
        all.add("AUDIT:\n" + String.join("\n", jdbc.queryForList("SELECT row_to_json(a)::text FROM audit.audit_entry a", String.class)));
        all.add("METRICS:\n" + Stream.concat(registries.orderedStream(), Stream.of(Metrics.globalRegistry))
                .flatMap(r -> r.getMeters().stream())
                .map(m -> m.getId().toString())
                .toList());
        assertThat(all).isNotEmpty();
        // Soft: report every surface that leaks, not just the first one.
        SoftAssertions soft = new SoftAssertions();
        for (String surface : all) {
            for (String canary : CANARIES) {
                soft.assertThat(surface)
                        .as("canary '%s' leaked via %s", canary, surface.lines().findFirst().orElse("?"))
                        .doesNotContainIgnoringCase(canary);
            }
        }
        soft.assertAll();
    }

    private static List<String> describe(IfscBankSourceException e) {
        List<String> out = new ArrayList<>();
        out.add("EXCEPTION " + e);
        out.add("EXCEPTION properties " + e.properties());
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            out.add("EXCEPTION cause " + t);
        }
        return out;
    }

    private String api(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return "API " + pathAndQuery.replaceAll("\\?.*", "") + " -> " + response.statusCode() + "\n"
                + response.headers().map() + "\n" + response.body();
    }

    /** Straight to the simulator. A timeout is fine (no body); anything that does come back is scanned. */
    private static String rawSimulatorCheck(String ifsc, String account, String fault) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(DepartmentSimulator.baseUrl() + "/v1/bank-checks"))
                .timeout(Duration.ofSeconds(1))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (DepartmentSimulator.KEY_ID + ":" + DepartmentSimulator.KEY_SECRET).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"ifsc\":\"" + ifsc + "\",\"accountNumber\":\"" + account + "\",\"applicantName\":\"Asha Patil\"}"));
        if (fault != null) {
            b.header("X-Samanvay-Simulator-Fault", fault);
        }
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return "SIMULATOR " + account + (fault == null ? "" : " fault=" + fault) + " -> " + r.statusCode() + "\n"
                    + r.headers().map() + "\n" + r.body();
        } catch (HttpTimeoutException e) {
            return "SIMULATOR timeout";
        }
    }

    /**
     * Test-only stand-in for the future journey endpoint. It lives outside /api,
     * so it needs no role, and it is nested in this test class, so no other
     * context scans it.
     */
    @RestController
    static class TestOnlyBankCheckEndpoint {

        private final IfscBankClient client;

        TestOnlyBankCheckEndpoint(IfscBankClient client) {
            this.client = client;
        }

        @GetMapping("/test-only/ifsc-bank/check")
        BankCheck check(@RequestParam String ifsc, @RequestParam String accountNumber, @RequestParam String applicantName) {
            return client.check(ifsc, accountNumber, applicantName);
        }

        @GetMapping("/test-only/ifsc-bank/ifsc/{ifsc}")
        IfscLookup ifsc(@PathVariable String ifsc) {
            return client.lookupIfsc(ifsc);
        }
    }
}
