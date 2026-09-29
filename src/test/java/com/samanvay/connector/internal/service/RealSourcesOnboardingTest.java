package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.connector.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.api.TransformCall;
import com.samanvay.catalog.api.ValidationResult;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.connector.internal.protocol.DeadlineHttp;
import com.samanvay.connector.internal.protocol.RealAdapters;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.sftp.SftpCsvClient;
import com.samanvay.connector.internal.source.sftp.SftpSourceProperties;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SecretStore;
import com.samanvay.shared.SubjectRef;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end proof that the real-mode sources seeded by V193 are onboarded correctly: the catalog rows
 * are read from the migration file itself (not re-typed here), and {@link ConnectorRuntimeImpl} runs a
 * FETCH per protocol through the REAL REST / SOAP / SFTP_CSV adapters against in-JVM fake servers
 * (two {@code HttpServer}s and an Apache MINA {@code SshServer}), mapping and validating the answer.
 *
 * <p>The seeded hosts are {@code .example} names that never resolve, so each test swaps only the
 * data source's base host for the fake server's address (SFTP takes its host from
 * {@code samanvay.sources.sftp.sources.<code>}, as in production). Nothing here needs Docker or a
 * database; the parts that need Postgres (the rows actually loading) are covered by Flyway in the ITs.
 */
class RealSourcesOnboardingTest {

    static final String MIGRATION = "/db/migration/V193__catalog_real_transport_sandbox_sources.sql";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String SFTP_CODE = "sandbox-property-sftp";
    static final String SFTP_USER = "sandbox-user";
    static final String SFTP_PASSWORD = "sandbox-password-Qm42";
    static final String PROPERTY_CSV = "propertyId,propertyRef,ward\nPROP-7,  WARD-09-7 ,Ward 9\nPROP-88,WARD-12-88,Ward 12\n";

    @TempDir
    Path sftpRoot;

    final AccessGrantVerifier verifier = mock(AccessGrantVerifier.class);
    final AuditService audit = mock(AuditService.class);
    final ConnectorCatalog catalog = mock(ConnectorCatalog.class);
    final SchemaCatalog schemas = mock(SchemaCatalog.class);

    Seed seed;
    HttpServer restServer;
    HttpServer soapServer;
    SshServer sftpServer;
    String sftpHostKeyFingerprint;

    final AtomicReference<String> restRequest = new AtomicReference<>();
    final AtomicReference<String> soapPath = new AtomicReference<>();
    final AtomicReference<String> soapBody = new AtomicReference<>();
    final AtomicReference<String> soapContentType = new AtomicReference<>();
    final AtomicInteger sftpLogins = new AtomicInteger();

    @BeforeEach
    void startFakes() throws Exception {
        seed = Seed.load();

        restServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        restServer.createContext("/", exchange -> {
            restRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            reply(exchange, "{\"annualIncome\":\" 742000 \",\"holderName\":\"  Sandbox Holder  \"}");
        });
        restServer.start();

        soapServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        soapServer.createContext("/", exchange -> {
            soapPath.set(exchange.getRequestURI().getPath());
            soapContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            soapBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<GetMarksResponse><percentage>91</percentage><board>icse</board></GetMarksResponse>"
                    + "</soap:Body></soap:Envelope>");
        });
        soapServer.start();

        Files.createDirectories(sftpRoot.resolve("outbound"));
        Files.writeString(sftpRoot.resolve("outbound/property.csv"), PROPERTY_CSV, StandardCharsets.UTF_8);
        sftpServer = SshServer.setUpDefaultServer();
        sftpServer.setHost("127.0.0.1");
        sftpServer.setPort(0);
        sftpServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sftpServer.setPasswordAuthenticator((user, password, session) -> {
            sftpLogins.incrementAndGet();
            return SFTP_USER.equals(user) && SFTP_PASSWORD.equals(password);
        });
        sftpServer.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sftpServer.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sftpServer.start();
        KeyPair hostKey = sftpServer.getKeyPairProvider().loadKeys(null).iterator().next();
        sftpHostKeyFingerprint = KeyUtils.getFingerPrint(hostKey.getPublic());
    }

    @AfterEach
    void stopFakes() throws IOException {
        restServer.stop(0);
        soapServer.stop(0);
        sftpServer.stop(true);
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    // ---- seed shape -------------------------------------------------------------------------

    @Test
    void seed_has_one_non_mock_source_per_protocol_and_only_names_secrets() {
        assertThat(seed.dataSources().values())
                .extracting(d -> d.get("protocol"))
                .containsExactlyInAnyOrder("REST", "SOAP", "SFTP_CSV");
        seed.dataSources().values().forEach(d -> {
            assertThat(d.get("base_host")).isNotEqualTo(RealAdapters.MOCK_HOST).endsWith(".example");
            assertThat(d.get("auth_config_ref")).startsWith("secret:");
            assertThat(d.get("auth_config_ref")).doesNotContain(SFTP_PASSWORD);
        });
        assertThat(seed.dataSources().get(SFTP_CODE).get("auth_config_ref")).isEqualTo("secret:" + SFTP_CODE);
        assertThat(seed.connectors()).hasSize(3);
        seed.connectors().values().forEach(c -> assertThat(c.get("status")).isEqualTo("PUBLISHED"));
    }

    // ---- end to end, one fetch per protocol -------------------------------------------------

    @Test
    void rest_source_fetches_real_data_through_the_runtime() {
        String host = "127.0.0.1:" + restServer.getAddress().getPort();
        ConnectorRuntimeImpl runtime = runtime("sandbox-income@1", host);

        ConnectorResult result = runtime.execute(grant("sandbox-income@1", "SANDBOX_INCOME"), Capability.FETCH,
                inputs("SANDBOX_INCOME", "RC-1&2"));

        ConnectorResult.Success success = assertSuccess(result);
        assertThat(restRequest.get()).isEqualTo("GET /v1/income?rationCard=RC-1%262");
        assertThat(success.canonical().get("annualIncome").asString()).isEqualTo("742000");
        assertThat(success.canonical().get("holderName").asString()).isEqualTo("Sandbox Holder");
        assertProvenanceAndAudit(success, "sandbox-income@1");
    }

    @Test
    void soap_source_fetches_real_data_through_the_runtime() {
        String host = "127.0.0.1:" + soapServer.getAddress().getPort();
        ConnectorRuntimeImpl runtime = runtime("sandbox-marks@1", host);

        ConnectorResult result = runtime.execute(grant("sandbox-marks@1", "SANDBOX_MARKS"), Capability.FETCH,
                inputs("SANDBOX_MARKS", "S-1&2"));

        ConnectorResult.Success success = assertSuccess(result);
        assertThat(soapPath.get()).isEqualTo("/marks/service");
        assertThat(soapContentType.get()).isEqualTo("text/xml; charset=utf-8");
        assertThat(soapBody.get()).contains("<studentId>S-1&amp;2</studentId>").doesNotContain("{{studentId}}");
        assertThat(success.canonical().get("percentage").asString()).isEqualTo("91");
        assertThat(success.canonical().get("board").asString()).isEqualTo("ICSE");
        assertProvenanceAndAudit(success, "sandbox-marks@1");
    }

    @Test
    void sftp_source_fetches_real_data_through_the_runtime_using_the_secretstore_credential() {
        // The catalog host stays the seeded .example one: the SFTP host comes from samanvay.sources.sftp.sources.<code>.
        ConnectorRuntimeImpl runtime = runtime("sandbox-property@1", null);

        ConnectorResult result = runtime.execute(grant("sandbox-property@1", "SANDBOX_PROPERTY"), Capability.FETCH,
                inputs("SANDBOX_PROPERTY", "PROP-7"));

        ConnectorResult.Success success = assertSuccess(result);
        assertThat(success.canonical().get("propertyRef").asString()).isEqualTo("WARD-09-7");
        assertThat(success.canonical().get("ward").asString()).isEqualTo("Ward 9");
        assertThat(sftpLogins.get()).as("the fake SFTP server really authenticated the runtime").isEqualTo(1);
        assertProvenanceAndAudit(success, "sandbox-property@1");
    }

    // ---- wiring -----------------------------------------------------------------------------

    /**
     * @param baseHostOverride replaces the seeded (never-resolving) host with a fake server; null keeps it
     */
    ConnectorRuntimeImpl runtime(String connectorRef, String baseHostOverride) {
        Map<String, String> connectorRow = seed.connectors().get(connectorRef);
        ConnectorDefinition connector = new ConnectorDefinition(
                connectorRow.get("ref"),
                connectorRow.get("connector_id"),
                Integer.parseInt(connectorRow.get("version")),
                connectorRow.get("data_source_code"),
                DataCategory.of(connectorRow.get("data_category")),
                connectorRow.get("capabilities"),
                connectorRow.get("inputs"),
                Integer.valueOf(connectorRow.get("sla_ms")),
                ConnectorStatus.valueOf(connectorRow.get("status")));
        Map<String, String> ds = seed.dataSources().get(connector.dataSourceCode());
        String baseHost = baseHostOverride != null ? baseHostOverride : ds.get("base_host");
        DataSourceDefinition dataSource = new DataSourceDefinition(
                ds.get("code"), ds.get("department_code"), ds.get("protocol"), baseHost, ds.get("auth_type"),
                ds.get("auth_config_ref"), null, null);
        assertThat(dataSource.baseHost()).isNotEqualTo(RealAdapters.MOCK_HOST);

        when(catalog.byRef(connectorRef)).thenReturn(connector);
        when(catalog.dataSourceFor(connector)).thenReturn(dataSource);
        Map<String, String> mappingRow = seed.mappings().values().stream()
                .filter(m -> m.get("connector_ref").equals(connectorRef))
                .findFirst()
                .orElseThrow();
        when(catalog.mapping(mappingRow.get("ref"))).thenReturn(mappingDefinition(mappingRow));
        when(schemas.validate(any(), any())).thenAnswer(inv -> validate(inv.getArgument(0), inv.getArgument(1)));

        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        SecretStore provisioned = new SecretStore() {
            @Override
            public Secret resolve(String key) {
                throw new AssertionError("source credentials must be read with find()");
            }

            @Override
            public java.util.Optional<Secret> find(String key) {
                // The catalog's auth_config_ref names this exact key.
                return SourceCredentials.secretKey(SFTP_CODE).equals(key)
                        ? java.util.Optional.of(new Secret((SFTP_USER + ":" + SFTP_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                        : java.util.Optional.empty();
            }
        };
        SftpCsvClient sftp = new SftpCsvClient(
                new SftpSourceProperties(Map.of(SFTP_CODE, new SftpSourceProperties.Source(
                        SourceMode.SANDBOX, "127.0.0.1", sftpServer.getPort(), "/outbound/property.csv", sftpHostKeyFingerprint,
                        Duration.ofSeconds(5), Duration.ofSeconds(10), null))),
                new SourceCredentials(provisioned));
        List<ProtocolAdapter> adapters = List.of(RealAdapters.rest(http), RealAdapters.soap(http), RealAdapters.sftp(sftp));

        DepartmentChaos chaos = mock(DepartmentChaos.class);
        return new ConnectorRuntimeImpl(
                verifier, catalog, schemas, adapters, new ResilienceRegistries(1, Duration.ofMillis(10)),
                new MappingExecutor(), audit, chaos, Duration.ofSeconds(10), code -> java.util.Optional.empty());
    }

    static MappingDefinition mappingDefinition(Map<String, String> mappingRow) {
        List<FieldMapping> rules = new ArrayList<>();
        for (JsonNode rule : JSON.readTree(mappingRow.get("rules"))) {
            List<TransformCall> transforms = new ArrayList<>();
            for (JsonNode t : rule.get("transforms")) {
                List<String> args = new ArrayList<>();
                t.get("args").forEach(a -> args.add(a.asString()));
                transforms.add(new TransformCall(t.get("fn").asString(), args));
            }
            rules.add(new FieldMapping(rule.get("source").asString(), rule.get("target").asString(), transforms));
        }
        return new MappingDefinition(mappingRow.get("ref"), mappingRow.get("connector_ref"), rules);
    }

    /** Same required-fields check the catalog applies, over the seeded schema definition. */
    ValidationResult validate(String schemaRef, JsonNode document) {
        JsonNode schema = JSON.readTree(seed.schemas().get(schemaRef).get("definition"));
        for (JsonNode required : schema.get("required")) {
            JsonNode value = document.get(required.asString());
            if (value == null || value.isNull()) {
                return new ValidationResult(false, List.of("missing " + required.asString()));
            }
        }
        return ValidationResult.ok();
    }

    static AccessGrant grant(String connectorRef, String category) {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()),
                null, DataCategory.of(category), "SANDBOX", connectorRef, null, null, Instant.now(),
                Instant.now().plusSeconds(60), new byte[0]);
    }

    static ExecutionInputs inputs(String category, String localIdToken) {
        return new ExecutionInputs(DataCategory.of(category), "wf-1", Map.of("localIdToken", localIdToken), Map.of(), Map.of());
    }

    static ConnectorResult.Success assertSuccess(ConnectorResult result) {
        assertThat(result).as("fetch through the real adapter").isInstanceOf(ConnectorResult.Success.class);
        return (ConnectorResult.Success) result;
    }

    void assertProvenanceAndAudit(ConnectorResult.Success success, String connectorRef) {
        assertThat(success.provenance().connectorRef()).isEqualTo(connectorRef);
        assertThat(success.provenance().departmentCode()).isEqualTo("SANDBOX");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("DATA_ACCESSED");
        assertThat(entry.getValue().resource()).isEqualTo(connectorRef);
    }

    // ---- reading the migration --------------------------------------------------------------

    /** The V193 rows, parsed straight from the migration file, keyed by primary key. */
    record Seed(
            Map<String, Map<String, String>> dataSources,
            Map<String, Map<String, String>> schemas,
            Map<String, Map<String, String>> connectors,
            Map<String, Map<String, String>> mappings) {

        static Seed load() throws IOException {
            String sql;
            try (InputStream in = RealSourcesOnboardingTest.class.getResourceAsStream(MIGRATION)) {
                assertThat(in).as(MIGRATION).isNotNull();
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            sql = sql.lines().filter(l -> !l.stripLeading().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
            return new Seed(
                    rows(sql, "catalog_data_source", "code"),
                    rows(sql, "catalog_schema", "ref"),
                    rows(sql, "catalog_connector", "ref"),
                    rows(sql, "catalog_mapping", "ref"));
        }

        /** Rows of the {@code INSERT INTO table (cols) VALUES (...),(...) ON CONFLICT ...;} statement. */
        static Map<String, Map<String, String>> rows(String sql, String table, String key) {
            int at = sql.indexOf("INSERT INTO " + table + " (");
            assertThat(at).as("INSERT INTO " + table).isNotNegative();
            int colsStart = sql.indexOf('(', at) + 1;
            int colsEnd = sql.indexOf(')', colsStart);
            List<String> cols = List.of(sql.substring(colsStart, colsEnd).split("\\s*,\\s*"));
            int valuesAt = sql.indexOf("VALUES", colsEnd) + "VALUES".length();
            int conflict = sql.indexOf("ON CONFLICT", valuesAt);
            assertThat(conflict).as(table + " insert must be idempotent (ON CONFLICT)").isNotNegative();

            List<String> literals = new ArrayList<>();
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            String body = sql.substring(valuesAt, conflict);
            int i = 0;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '\'') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (true) {
                        char d = body.charAt(i);
                        if (d == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else if (d == '\'') {
                            i++;
                            break;
                        } else {
                            sb.append(d);
                            i++;
                        }
                    }
                    literals.add(sb.toString());
                } else if (Character.isDigit(c)) {
                    int j = i;
                    while (j < body.length() && Character.isDigit(body.charAt(j))) {
                        j++;
                    }
                    literals.add(body.substring(i, j));
                    i = j;
                } else if (body.startsWith("NULL", i)) {
                    literals.add(null);
                    i += 4;
                } else if (c == ')') {
                    assertThat(literals).as(table + " tuple arity").hasSize(cols.size());
                    Map<String, String> row = new HashMap<>();
                    for (int k = 0; k < cols.size(); k++) {
                        row.put(cols.get(k), literals.get(k));
                    }
                    out.put(row.get(key), row);
                    literals = new ArrayList<>();
                    i++;
                } else {
                    i++;
                }
            }
            return out;
        }
    }
}
