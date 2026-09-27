package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapters;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.web.SourceOutcomeProblems;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.SecretStore;
import com.samanvay.shared.test.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.lang.reflect.RecordComponent;
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
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canary leak test. Two canaries live only in the simulator's fixture file:
 * <ul>
 *   <li>the holder names ("SIMULATED Canary ..."). Bank-check contract v1 never
 *       returns them, so they may never appear anywhere on our side;
 *   <li>a distinctive ACCOUNT NUMBER, which we do send. The full number may never
 *       come back in a response, log line, audit row or problem detail. (Only a
 *       future "account ending" copy may show its last four digits; that copy is
 *       not in this PR.)
 * </ul>
 * Surfaces scanned:
 * <ul>
 *   <li>every outcome, rendered in full ({@code toString} plus the full cause chain
 *       of any Throwable inside it; there must be none);
 *   <li>API response and problem-detail bodies, from a test-only endpoint that uses
 *       the real adapter (looked up through {@link BankCheckAdapters}) and the real
 *       API-layer mapper {@link SourceOutcomeProblems};
 *   <li>captured log lines (DEBUG for the connector);
 *   <li>every {@code audit.audit_entry} row, as JSON;
 *   <li>every metric id and tag;
 *   <li>the simulator's own raw responses, faults included.
 * </ul>
 * Paths covered: success (all seven fixture verdicts, the canary account included),
 * timeout, 503 and truncated JSON, for both bank check and IFSC lookup; the fault
 * paths are driven with the canary account number too.
 *
 * <p>Nothing on this path writes audit rows or metrics yet, so those two checks
 * are tripwires for the journey PR.
 */
@SpringBootTest(
        classes = SamanvayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "samanvay.sources.ifsc-bank.mode=simulator",
            "samanvay.sources.ifsc-bank.read-timeout=1s",
            "logging.level.com.samanvay.connector=DEBUG"
        })
@Import({BankCheckCanaryLeakIT.TestOnlyBankCheckEndpoint.class, BankCheckCanaryLeakIT.SimulatorCredential.class})
@ExtendWith(OutputCaptureExtension.class)
class BankCheckCanaryLeakIT extends PostgresIntegrationTest {

    static final List<String> HOLDER_CANARIES = DepartmentSimulator.canaryHolderNames();
    static final String CANARY_ACCOUNT = DepartmentSimulator.canaryAccountNumber();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void simulator(DynamicPropertyRegistry registry) {
        registry.add("samanvay.sources.ifsc-bank.base-url", () -> DepartmentSimulator.baseUrl().toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    BankCheckAdapters adapters;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectProvider<MeterRegistry> registries;

    final List<String> surfaces = new ArrayList<>();

    BankCheckAdapter adapter() {
        return adapters.forSource(IfscBankClient.SOURCE_CODE).orElseThrow();
    }

    @BeforeEach
    void canariesAreReal() {
        assertThat(HOLDER_CANARIES).hasSize(7).allSatisfy(c -> assertThat(c).startsWith("SIMULATED Canary "));
        assertThat(CANARY_ACCOUNT).hasSize(14).doesNotStartWith("0000");
    }

    @Test
    void outcome_types_cannot_carry_a_cause_or_body_text() {
        for (Class<?> type : SourceOutcome.class.getPermittedSubclasses()) {
            for (RecordComponent c : type.getRecordComponents()) {
                assertThat(Throwable.class.isAssignableFrom(c.getType()))
                        .as("%s.%s must not be a Throwable", type.getSimpleName(), c.getName())
                        .isFalse();
                assertThat(c.getType())
                        .as("%s.%s must not be free text", type.getSimpleName(), c.getName())
                        .isNotEqualTo(String.class);
            }
        }
    }

    @Test
    void success_paths_leak_nothing(CapturedOutput output) throws Exception {
        String[][] accounts = {
            {"SBIN0000300", "00001000000001"}, {"MAHB0000001", "00001000000002"}, {"HDFC0000001", "00001000000003"},
            {"SBIN0001593", "00001000000006"}, {"BKID0000150", "00001000000004"}, {"UTIB0000004", "00001000000005"},
            {"SBIN0000300", CANARY_ACCOUNT}
        };
        for (String[] a : accounts) {
            SourceOutcome<?> outcome = adapter().check(new BankCheckRequest(a[0], a[1], "Asha Patil"));
            assertThat(outcome).isInstanceOf(SourceOutcome.Answered.class);
            surfaces.add(render(outcome));
            surfaces.add(api("/test-only/ifsc-bank/check?ifsc=" + a[0] + "&accountNumber=" + a[1] + "&applicantName=Asha%20Patil"));
            surfaces.add(rawSimulatorCheck(a[0], a[1], null));
        }
        surfaces.add(render(adapter().lookupIfsc("SBIN0000300")));
        surfaces.add(api("/test-only/ifsc-bank/ifsc/SBIN0000300"));
        assertNoCanary(output);
    }

    @Test
    void timeout_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000408", "SAMS0000408", "timeout", SourceOutcome.SourceTimeout.class);
        assertNoCanary(output);
    }

    @Test
    void server_error_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000500", "SAMS0000500", "server_error", SourceOutcome.SourceFault.class);
        assertNoCanary(output);
    }

    @Test
    void truncated_json_path_leaks_nothing(CapturedOutput output) throws Exception {
        faultPath("00009000000422", "SAMS0000422", "malformed", SourceOutcome.SourceFault.class);
        assertNoCanary(output);
    }

    /** One fault via the adapter, the API endpoint and the raw simulator; also with the canary account number. */
    private void faultPath(String triggerAccount, String triggerIfsc, String headerFault, Class<?> expected) throws Exception {
        for (BankCheckRequest request : List.of(
                new BankCheckRequest("SBIN0000300", triggerAccount, "Asha Patil"),
                new BankCheckRequest(triggerIfsc, CANARY_ACCOUNT, "Asha Patil"))) {
            SourceOutcome<?> outcome = adapter().check(request);
            assertThat(outcome).isInstanceOf(expected);
            surfaces.add(render(outcome));
            String problem = api("/test-only/ifsc-bank/check?ifsc=" + request.ifsc() + "&accountNumber="
                    + request.accountNumber() + "&applicantName=Asha%20Patil");
            assertThat(problem).contains("\"source\":\"ifsc-bank\"");
            surfaces.add(problem);
        }
        SourceOutcome<?> lookup = adapter().lookupIfsc(triggerIfsc);
        assertThat(lookup).isInstanceOf(expected);
        surfaces.add(render(lookup));
        surfaces.add(api("/test-only/ifsc-bank/ifsc/" + triggerIfsc));
        surfaces.add(rawSimulatorCheck("SBIN0000300", triggerAccount, null));
        surfaces.add(rawSimulatorCheck(triggerIfsc, CANARY_ACCOUNT, null));
        for (String a : new String[] {
            "00001000000001", "00001000000002", "00001000000003", "00001000000006", "00001000000004", "00001000000005", CANARY_ACCOUNT
        }) {
            surfaces.add(rawSimulatorCheck("SBIN0000300", a, headerFault));
        }
    }

    private void assertNoCanary(CapturedOutput output) {
        // Proves the capture sees the adapter's own log lines.
        assertThat(output.getAll()).contains("ifsc-bank ");
        List<String> all = new ArrayList<>(surfaces);
        all.add("LOGS\n" + output.getAll());
        all.add("AUDIT\n" + String.join("\n", jdbc.queryForList("SELECT row_to_json(a)::text FROM audit.audit_entry a", String.class)));
        all.add("METRICS\n" + Stream.concat(registries.orderedStream(), Stream.of(Metrics.globalRegistry))
                .flatMap(r -> r.getMeters().stream())
                .map(m -> m.getId().toString())
                .toList());
        SoftAssertions soft = new SoftAssertions();
        for (String surface : all) {
            String label = surface.lines().findFirst().orElse("?");
            for (String canary : HOLDER_CANARIES) {
                soft.assertThat(surface).as("holder canary '%s' leaked via %s", canary, label).doesNotContainIgnoringCase(canary);
            }
            soft.assertThat(surface).as("canary account number leaked via %s", label).doesNotContain(CANARY_ACCOUNT);
            if (label.startsWith("OUTCOME ")) {
                soft.assertThat(surface).as("outcome carries a cause: %s", label).doesNotContain("\n  cause: ");
            }
        }
        soft.assertAll();
    }

    /** The outcome in full: toString plus the cause chain of any Throwable found in its components. */
    static String render(SourceOutcome<?> outcome) {
        StringBuilder out = new StringBuilder("OUTCOME ").append(outcome);
        for (RecordComponent c : outcome.getClass().getRecordComponents()) {
            Object value;
            try {
                value = c.getAccessor().invoke(outcome);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            for (Throwable t = value instanceof Throwable th ? th : null; t != null; t = t.getCause()) {
                out.append("\n  cause: ").append(t);
            }
        }
        return out.toString();
    }

    private String api(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        // The label drops the query string: it is OUR request, not something that came back.
        return "API " + pathAndQuery.replaceAll("\\?.*", "") + " -> " + response.statusCode() + "\n"
                + response.headers().map() + "\n" + response.body();
    }

    /** Straight to the simulator. A timeout returns no body; anything that does come back is scanned. */
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
        String label = "SIMULATOR " + ifsc + (account.equals(CANARY_ACCOUNT) ? " <canary account>" : " " + account)
                + (fault == null ? "" : " fault=" + fault);
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return label + " -> " + r.statusCode() + "\n" + r.headers().map() + "\n" + r.body();
        } catch (HttpTimeoutException e) {
            return label + " -> timeout";
        }
    }

    /** The simulator credential, served through SecretStore (as in every mode); everything else goes to the real store. */
    @TestConfiguration
    static class SimulatorCredential {
        @Bean
        @Primary
        SecretStore simulatorCredentialSecretStore() {
            EnvSecretStore real = new EnvSecretStore();
            SecretStore credential = DepartmentSimulator.secretStore(DepartmentSimulator.KEY_ID + ":" + DepartmentSimulator.KEY_SECRET);
            String key = SourceCredentials.secretKey(IfscBankClient.SOURCE_CODE);
            return new SecretStore() {
                @Override
                public Secret resolve(String k) {
                    return key.equals(k) ? credential.resolve(k) : real.resolve(k);
                }

                @Override
                public Optional<Secret> find(String k) {
                    return key.equals(k) ? credential.find(k) : real.find(k);
                }
            };
        }
    }

    /**
     * Test-only stand-in for the future journey endpoint: adapter from the registry,
     * failures through the API-layer mapper. It lives outside /api, so it needs no
     * role, and it is nested in this test class, so no other context scans it.
     */
    @RestController
    static class TestOnlyBankCheckEndpoint {

        private final BankCheckAdapters adapters;

        TestOnlyBankCheckEndpoint(BankCheckAdapters adapters) {
            this.adapters = adapters;
        }

        @GetMapping("/test-only/ifsc-bank/check")
        ResponseEntity<?> check(@RequestParam String ifsc, @RequestParam String accountNumber, @RequestParam String applicantName) {
            return render(adapters.forSource(IfscBankClient.SOURCE_CODE).orElseThrow()
                    .check(new BankCheckRequest(ifsc, accountNumber, applicantName)));
        }

        @GetMapping("/test-only/ifsc-bank/ifsc/{ifsc}")
        ResponseEntity<?> ifsc(@PathVariable String ifsc) {
            return render(adapters.forSource(IfscBankClient.SOURCE_CODE).orElseThrow().lookupIfsc(ifsc));
        }

        private static ResponseEntity<?> render(SourceOutcome<?> outcome) {
            if (outcome instanceof SourceOutcome.Answered<?> answered) {
                return ResponseEntity.ok(answered.answer());
            }
            var problem = SourceOutcomeProblems.toProblem(IfscBankClient.SOURCE_CODE, outcome);
            return ResponseEntity.status(problem.getStatus()).body(problem);
        }
    }
}
