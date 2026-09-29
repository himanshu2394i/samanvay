package com.samanvay.shared.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * In-process real department services for integration tests: the endpoints behind the journey
 * sources repointed by V199 (INCOME/MARKS/BANK over REST/SOAP, PROPERTY over SFTP, POLLUTION over
 * JDBC). Real transport, fake data, no Docker — the {@link com.samanvay.connector.internal.protocol}
 * adapters cross real sockets to reach them, exactly as they would the department containers.
 *
 * <p>Started once per JVM and pointed at by {@link #register(DynamicPropertyRegistry)}, which every
 * {@code RealDepartmentsIT} calls. Credentials come from the test env vars set in the failsafe/surefire
 * config (fixtureuser:fixturepass, pcb_ro:pcb_ro_demo).
 */
public final class RealDepartments {

    private static final String PROPERTY_CSV =
            "propertyId,propertyRef,ward\nPROP-88,WARD-12-88,Ward 12\nPROP-1,WARD-01-1,Ward 1\n";

    private static volatile boolean started;
    private static String httpUrl;
    private static int sftpPort;
    private static String sftpFingerprint;
    private static String jdbcUrl;
    private static Connection h2KeepAlive;

    private RealDepartments() {}

    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        try {
            startHttp();
            startSftp();
            startH2();
            started = true;
        } catch (Exception e) {
            throw new IllegalStateException("could not start RealDepartments fixtures", e);
        }
    }

    /** Registers a pollution-clearance record for a premise id, as a real department would hold on file. */
    public static synchronized void seedPremise(String premiseId) {
        ensureStarted();
        try (Statement st = h2KeepAlive.createStatement()) {
            st.execute("MERGE INTO pcb_clearance (premise_id, clearance_status, holder) KEY(premise_id) VALUES ('"
                    + premiseId.replace("'", "''") + "', 'clear', 'Fixture Holder')");
        } catch (Exception e) {
            throw new IllegalStateException("could not seed premise " + premiseId, e);
        }
    }

    /** Points the V199 journey sources at these fixtures (overrides the demo-profile localhost values). */
    public static void register(DynamicPropertyRegistry registry) {
        ensureStarted();
        for (String code : List.of("dept-income-rest", "dept-marks-soap", "dept-bank-rest")) {
            registry.add("samanvay.sources.department-service.urls." + code, () -> httpUrl);
        }
        String sftp = "samanvay.sources.sftp.sources.dept-property-sftp.";
        registry.add(sftp + "mode", () -> "simulator");
        registry.add(sftp + "host", () -> "127.0.0.1");
        registry.add(sftp + "port", () -> sftpPort);
        registry.add(sftp + "remote-path", () -> "/outbound/property.csv");
        registry.add(sftp + "host-key-sha256", () -> sftpFingerprint);
        String jdbc = "samanvay.sources.jdbc.sources.dept-pollution-jdbc.";
        registry.add(jdbc + "mode", () -> "simulator");
        registry.add(jdbc + "jdbc-url", () -> jdbcUrl);
    }

    private static void startHttp() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/income", ex -> respondJson(ex,
                "{\"annualIncome\":\"742000\",\"annualIncomeDisplay\":\"Rs 7,42,000\",\"holderName\":\"Real Holder\","
                        + "\"district\":\"Pune\",\"issuerOffice\":\"Tahsildar, Haveli\"}"));
        server.createContext("/bank", ex -> respondJson(ex,
                "{\"accountRef\":\"XXXXXX1234\",\"ifscMasked\":\"SBIN0XXX300\",\"holderName\":\"Real Holder\"}"));
        server.createContext("/marks/service", ex -> respondXml(ex,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soap:Body><GetMarksResponse><percentage>81</percentage><board>icse</board><exam>HSC 2025</exam>"
                        + "</GetMarksResponse></soap:Body></soap:Envelope>"));
        server.setExecutor(null);
        server.start();
        httpUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    private static void respondJson(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        write(ex, body);
    }

    private static void respondXml(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
        write(ex, body);
    }

    private static void write(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void startSftp() throws Exception {
        Path root = Files.createTempDirectory("real-dept-sftp");
        Files.createDirectories(root.resolve("outbound"));
        Files.writeString(root.resolve("outbound/property.csv"), PROPERTY_CSV, StandardCharsets.UTF_8);
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, session) -> "fixtureuser".equals(user) && "fixturepass".equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        sftpPort = server.getPort();
        KeyPair hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
        sftpFingerprint = KeyUtils.getFingerPrint(hostKey.getPublic());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(true);
            } catch (IOException ignored) {
                // JVM is exiting.
            }
        }));
    }

    private static void startH2() throws Exception {
        String db = "dept_pollution_" + System.nanoTime();
        jdbcUrl = "jdbc:h2:mem:" + db;
        // Admin keep-alive holds the in-memory DB open for the JVM; the read-only user connects bare.
        h2KeepAlive = DriverManager.getConnection(jdbcUrl + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = h2KeepAlive.createStatement()) {
            st.execute("CREATE TABLE pcb_clearance (premise_id VARCHAR(64) PRIMARY KEY, clearance_status VARCHAR(32), holder VARCHAR(64))");
            st.execute("INSERT INTO pcb_clearance VALUES ('PCB-1', 'clear', 'Acme Textiles')");
            st.execute("INSERT INTO pcb_clearance VALUES ('PR-1001', 'clear', 'Ganesh Foods')");
            st.execute("CREATE USER pcb_ro PASSWORD 'pcb_ro_demo'");
            st.execute("GRANT SELECT ON pcb_clearance TO pcb_ro");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                h2KeepAlive.close();
            } catch (Exception ignored) {
                // JVM is exiting.
            }
        }));
    }
}
