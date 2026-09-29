package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.api.ValidationResult;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.connector.internal.protocol.DeadlineHttp;
import com.samanvay.connector.internal.protocol.RealAdapters;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.shared.DataCategory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proof that the platform's REAL REST and SOAP adapters fetch over the network from a separate,
 * standalone department service, not from the in-process {@code MockDepartmentBackend}.
 *
 * <p>The service is {@code simulators/} (its own Maven project, its own JVM): this test builds its jar,
 * starts it as an OS process on a free port, and points the two V193 sandbox sources at it the way the
 * dev/demo profile does ({@code samanvay.sources.department-service.urls}). No Docker is needed. The
 * catalog rows are the V193 migration's own, so their hosts stay the unresolvable {@code *.example}
 * names: the only way a value can come back is a real HTTP exchange with the other process. The values
 * asserted are ones only that service produces (the mock backend answers 81 and INCOME-AMT-998877).
 */
class StandaloneDepartmentServiceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient DIRECT = HttpClient.newHttpClient();

    static Process service;
    static String serviceUrl;

    @BeforeAll
    static void startTheStandaloneService() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path jar = root.resolve("simulators/target/samanvay-simulators.jar");
        // Build the service like a developer would. Online first (CI), then offline (a warm ~/.m2 only).
        if (!mvn(root, false) && !mvn(root, true)) {
            throw new IllegalStateException("could not build simulators/ (see the Maven output above)");
        }
        assertThat(jar).exists();

        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        service = new ProcessBuilder(java, "-jar", jar.toString(), "--server.port=" + port)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(root.resolve("target/standalone-department-service.log").toFile()))
                .start();
        serviceUrl = "http://127.0.0.1:" + port;
        waitUntilServing();
    }

    @AfterAll
    static void stopTheService() throws InterruptedException {
        if (service != null) {
            service.destroy();
            if (!service.waitFor(10, TimeUnit.SECONDS)) {
                service.destroyForcibly();
            }
        }
    }

    static boolean mvn(Path root, boolean offline) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(root.resolve("mvnw").toString(), "-B", "-q", "-DskipTests",
                "-f", root.resolve("simulators/pom.xml").toString(), "package"));
        if (offline) {
            cmd.add(1, "-o");
        }
        Files.createDirectories(root.resolve("target"));
        Process p = new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("target/standalone-department-service-build.log").toFile())
                .start();
        return p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0;
    }

    static void waitUntilServing() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(service.isAlive()).as("the department service process is running").isTrue();
            try {
                if (direct("/v1/income?rationCard=RC-1001").statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("department service did not start on " + serviceUrl);
    }

    static HttpResponse<String> direct(String pathAndQuery) throws Exception {
        return DIRECT.send(HttpRequest.newBuilder(URI.create(serviceUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---- the fetches, through ConnectorRuntimeImpl and the REAL adapters ---------------------------

    @Test
    void rest_source_fetches_income_over_the_network_from_the_standalone_service() throws Exception {
        Fetch fetch = new Fetch("sandbox-income@1");

        ConnectorResult.Success known = fetch.run("SANDBOX_INCOME", "RC-1001");
        assertThat(known.canonical().get("annualIncome").asString()).isEqualTo("742000");
        assertThat(known.canonical().get("holderName").asString()).isEqualTo("Sandbox Holder");
        assertThat(known.provenance().departmentCode()).isEqualTo("SANDBOX");

        // A different card, answered by the service's own logic: the same as asking the service directly.
        ConnectorResult.Success other = fetch.run("SANDBOX_INCOME", "RC-ZZ-42");
        JsonNode direct = JSON.readTree(direct("/v1/income?rationCard=RC-ZZ-42").body());
        assertThat(other.canonical().get("annualIncome").asString()).isEqualTo(direct.get("annualIncome").asString());
        assertThat(other.canonical().get("holderName").asString()).isEqualTo(direct.get("holderName").asString());

        // Not the in-process mock backend's answer.
        assertThat(known.canonical().get("annualIncome").asString()).isNotEqualTo("INCOME-AMT-998877");
    }

    @Test
    void soap_source_fetches_marks_over_the_network_from_the_standalone_service() {
        Fetch fetch = new Fetch("sandbox-marks@1");

        ConnectorResult.Success known = fetch.run("SANDBOX_MARKS", "S-1001");
        assertThat(known.canonical().get("percentage").asString()).isEqualTo("91");
        assertThat(known.canonical().get("board").asString()).as("upper-cased by the mapping").isEqualTo("ICSE");

        ConnectorResult.Success other = fetch.run("SANDBOX_MARKS", "S-1002");
        assertThat(other.canonical().get("percentage").asString()).isEqualTo("81");
        assertThat(other.canonical().get("board").asString()).isEqualTo("CBSE");

        // S-9-DERIVED is computed by the service from the id alone (nothing the mock backend can know).
        ConnectorResult.Success derived = fetch.run("SANDBOX_MARKS", "S-9-DERIVED");
        assertThat(derived.canonical().get("percentage").asString()).matches("[5-9][0-9]");
        assertThat(fetch.run("SANDBOX_MARKS", "S-9-DERIVED").canonical()).isEqualTo(derived.canonical());
    }

    @Test
    void both_seeded_hosts_are_unresolvable_so_only_the_service_could_have_answered() {
        Seed seed = new Seed();
        assertThat(seed.host("sandbox-income-rest")).isEqualTo("rest.sandbox.samanvay.example");
        assertThat(seed.host("sandbox-marks-soap")).isEqualTo("soap.sandbox.samanvay.example");
    }

    // ---- wiring ------------------------------------------------------------------------------------

    /** The V193 rows, parsed from the migration file itself. */
    static class Seed {
        final RealSourcesOnboardingTest.Seed rows;

        Seed() {
            try {
                rows = RealSourcesOnboardingTest.Seed.load();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String host(String dataSourceCode) {
            return rows.dataSources().get(dataSourceCode).get("base_host");
        }
    }

    /** ConnectorRuntimeImpl over one V193 connector, the real adapters, and the dev/demo override to the service. */
    static class Fetch {
        final String connectorRef;
        final ConnectorRuntimeImpl runtime;

        Fetch(String connectorRef) {
            this.connectorRef = connectorRef;
            RealSourcesOnboardingTest.Seed seed = new Seed().rows;
            Map<String, String> row = seed.connectors().get(connectorRef);
            ConnectorDefinition connector = new ConnectorDefinition(
                    row.get("ref"), row.get("connector_id"), Integer.parseInt(row.get("version")),
                    row.get("data_source_code"), DataCategory.of(row.get("data_category")), row.get("capabilities"),
                    row.get("inputs"), Integer.valueOf(row.get("sla_ms")), ConnectorStatus.valueOf(row.get("status")));
            Map<String, String> ds = seed.dataSources().get(connector.dataSourceCode());
            // The catalog row exactly as seeded: the *.example host, untouched.
            DataSourceDefinition dataSource = new DataSourceDefinition(
                    ds.get("code"), ds.get("department_code"), ds.get("protocol"), ds.get("base_host"), ds.get("auth_type"),
                    ds.get("auth_config_ref"), null, null);

            ConnectorCatalog catalog = mock(ConnectorCatalog.class);
            when(catalog.byRef(connectorRef)).thenReturn(connector);
            when(catalog.dataSourceFor(connector)).thenReturn(dataSource);
            Map<String, String> mappingRow = seed.mappings().values().stream()
                    .filter(m -> m.get("connector_ref").equals(connectorRef))
                    .findFirst()
                    .orElseThrow();
            when(catalog.mapping(mappingRow.get("ref"))).thenReturn(RealSourcesOnboardingTest.mappingDefinition(mappingRow));
            SchemaCatalog schemas = mock(SchemaCatalog.class);
            when(schemas.validate(any(), any())).thenReturn(ValidationResult.ok());

            DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
            Map<String, String> overrides = Map.of("sandbox-income-rest", serviceUrl, "sandbox-marks-soap", serviceUrl);
            runtime = new ConnectorRuntimeImpl(
                    mock(AccessGrantVerifier.class), catalog, schemas,
                    List.of(RealAdapters.rest(http, overrides), RealAdapters.soap(http, overrides)),
                    new ResilienceRegistries(1, Duration.ofMillis(10)), new MappingExecutor(), mock(AuditService.class),
                    mock(DepartmentChaos.class), Duration.ofSeconds(10), code -> Optional.empty(),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        ConnectorResult.Success run(String category, String localIdToken) {
            ConnectorResult result = runtime.execute(
                    RealSourcesOnboardingTest.grant(connectorRef, category), Capability.FETCH,
                    RealSourcesOnboardingTest.inputs(category, localIdToken));
            return RealSourcesOnboardingTest.assertSuccess(result);
        }
    }
}
