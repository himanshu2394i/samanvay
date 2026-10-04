package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.samanvay.connector.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.DepartmentLogin;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresContainerSupport;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole chain, for real, for all four departments: the department's own process serves its manifest and login; Samanvay
 * onboards it in one go; the citizen logs in at the department and the signed assertion is verified and saved as a link;
 * then a document is fetched through the real connector runtime over the declared protocol and security.
 *
 * <p>Real here: the four department jars (REST+API key+resolve, REST+OAuth2, SOAP+WS-Security), an SFTP server for each of
 * Revenue and Agriculture (in-process Apache MINA, serving the departments' own CSV files), Agriculture's own Postgres
 * seeded by the department's own {@code db/init.sql} and read through the read-only account, the real database, the real
 * adapters and the real signed login assertions. Only the consent-grant check is stubbed (not what is under test).
 *
 * <p>Needs the department jars: {@code scripts/build-departments.sh} (the test is skipped, not failed, if they are missing).
 */
@SpringBootTest(
        classes = SamanvayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "samanvay.catalog.allowed-private-hosts=127.0.0.1,localhost",
            "samanvay.identity.department-assertion.allow-private-hosts=true",
            "samanvay.identity.department-assertion.allowed-return-prefixes=http://127.0.0.1:"
        })
@Import(DepartmentsEndToEndIT.ProvisionedSecrets.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DepartmentsEndToEndIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient(); // does not follow redirects
    /** The old Samanvay-side return address is gone; these tests now return to a department portal's callback (see start()). */
    static String returnTo(String loginDept) {
        return "http://127.0.0.1:" + PORT.get("revenue".equals(loginDept) ? "dbt" : "revenue") + "/portal/callback";
    }

    static final Path ROOT = findRoot();
    static final String REVENUE_DISCOVERY_KEY = "revenue-discovery-key-for-samanvay";

    // --- what the test provisions into the SecretStore, exactly as an operator would -------------------------------
    static final Map<String, String> SECRETS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class ProvisionedSecrets {
        @Bean
        @Primary
        EnvSecretStore provisionedSecretStore() {
            return new EnvSecretStore() {
                @Override
                public java.util.Optional<Secret> find(String key) {
                    String v = SECRETS.get(key);
                    return v != null ? java.util.Optional.of(new Secret(v.getBytes(StandardCharsets.UTF_8))) : super.find(key);
                }
            };
        }
    }

    // --- the world outside Samanvay --------------------------------------------------------------------------------
    static final Map<String, Integer> PORT = new LinkedHashMap<>();
    static final List<Process> PROCESSES = new ArrayList<>();
    static SshServer revenueSftp;
    static SshServer agricultureSftp;
    static String revenueFingerprint;
    static String agricultureFingerprint;
    static PostgreSQLContainer agriDb;
    static boolean jarsPresent;
    static int samanvayPort;
    static com.sun.net.httpserver.HttpServer tokenServer;

    static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("departments"))) {
            p = p.getParent();
        }
        return p;
    }

    static Path jar(String dept) {
        return ROOT.resolve("departments/" + dept + "/target/samanvay-dept-" + dept + ".jar");
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static {
        try {
            jarsPresent = List.of("revenue", "dbt", "education", "agriculture").stream().allMatch(d -> Files.exists(jar(d)));
            if (jarsPresent) {
                startWorld();
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not start the departments", e);
        }
    }

    static SshServer sftp(Path file, String user, String pass, String dirName) throws Exception {
        Path root = Files.createTempDirectory("sftp-" + dirName);
        Files.createDirectories(root.resolve("outbound"));
        Files.copy(file, root.resolve("outbound").resolve(file.getFileName()));
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        s.setPasswordAuthenticator((u, p, session) -> user.equals(u) && pass.equals(p));
        s.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        s.setFileSystemFactory(new VirtualFileSystemFactory(root));
        s.start();
        return s;
    }

    static String fingerprint(SshServer s) throws Exception {
        KeyPair k = s.getKeyPairProvider().loadKeys(null).iterator().next();
        return KeyUtils.getFingerPrint(k.getPublic());
    }

    /**
     * A stand-in for Keycloak's token endpoint: hands each department portal the staff-realm token Samanvay's test keys accept, with the
     * department claim of its client (dept-<code>-it). The real Keycloak client credentials flow is checked by the Keycloak ITs.
     */
    static void startTokenServer() throws IOException {
        tokenServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String clientId = URLDecoder.decode(form.replaceAll(".*client_id=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            String dept = clientId.replace("dept-", "").replace("-it", "").toUpperCase();
            byte[] out = ("{\"access_token\":\"" + com.samanvay.shared.test.TestTokens.departmentOf(clientId, dept) + "\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        tokenServer.start();
    }

    static void startWorld() throws Exception {
        samanvayPort = freePort();
        startTokenServer();
        revenueSftp = sftp(ROOT.resolve("departments/revenue/sftp/712.csv"), "revenue-sftp", "rev-sftp-pass", "rev");
        agricultureSftp = sftp(ROOT.resolve("departments/agriculture/sftp/crop.csv"), "agri-sftp", "agri-sftp-pass", "agri");
        revenueFingerprint = fingerprint(revenueSftp);
        agricultureFingerprint = fingerprint(agricultureSftp);

        agriDb = new PostgreSQLContainer("postgres:16").withDatabaseName("agridb").withUsername("agri_admin").withPassword("agri_admin_demo")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("departments/agriculture/db/init.sql")),
                        "/docker-entrypoint-initdb.d/init.sql")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
        agriDb.start();

        for (String d : List.of("revenue", "dbt", "education", "agriculture")) {
            PORT.put(d, freePort());
        }
        List<String> common = List.of("--spring.main.banner-mode=off");
        // Revenue also wants a discovery credential before it shows its manifest; Samanvay is given it below.
        start("revenue", "--revenue.public-base-url=http://127.0.0.1:" + PORT.get("revenue"), "--revenue.sftp.host=127.0.0.1",
                "--revenue.sftp.port=" + revenueSftp.getPort(), "--revenue.manifest.discovery-key=" + REVENUE_DISCOVERY_KEY);
        start("dbt", "--dbt.public-base-url=http://127.0.0.1:" + PORT.get("dbt"));
        start("education", "--education.public-base-url=http://127.0.0.1:" + PORT.get("education"));
        start("agriculture", "--agriculture.public-base-url=http://127.0.0.1:" + PORT.get("agriculture"), "--agriculture.db.host=" + agriDb.getHost(),
                "--agriculture.db.port=" + agriDb.getMappedPort(5432), "--agriculture.sftp.host=127.0.0.1",
                "--agriculture.sftp.port=" + agricultureSftp.getPort());
        for (String d : PORT.keySet()) {
            awaitManifest(d);
        }

        // Exactly what an operator provisions: the credential each department gave Samanvay (dev defaults of the stand-ins).
        SECRETS.put("manifest-127-0-0-1-" + PORT.get("revenue") + "-credential", REVENUE_DISCOVERY_KEY);
        SECRETS.put("source-revenue-rest-credential", "{\"X-Api-Key\":\"revenue-dev-key-change-me\"}");
        SECRETS.put("source-revenue-sftp-credential", "revenue-sftp:rev-sftp-pass");
        SECRETS.put("source-dbt-rest-credential", "{\"client_id\":\"samanvay-dev\",\"client_secret\":\"dbt-dev-secret-change-me\"}");
        SECRETS.put("source-education-soap-credential", "{\"username\":\"samanvay-dev\",\"password\":\"education-dev-secret-change-me\"}");
        SECRETS.put("source-agriculture-jdbc-credential", "agri_ro:agri_ro_demo");
        SECRETS.put("source-agriculture-sftp-credential", "agri-sftp:agri-sftp-pass");
    }

    static void start(String dept, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                jar(dept).toString(), "--server.port=" + PORT.get(dept), "--spring.main.banner-mode=off"));
        cmd.addAll(List.of(args));
        // Each department signs its manifest with a key kept in a file; keep the test's keys out of the source tree.
        Path keys = Path.of("target", "e2e-keys");
        Files.createDirectories(keys);
        cmd.add("--" + dept + ".manifest.key-file=" + keys.resolve(dept + ".jwk").toAbsolutePath());
        // The portal backend: reaches Samanvay with this department's own client, and may send citizens to the OTHER portals' logins
        // and receive them back at its own callback.
        cmd.add("--portal.public-base-url=http://127.0.0.1:" + PORT.get(dept));
        cmd.add("--portal.samanvay.base-url=http://127.0.0.1:" + samanvayPort);
        cmd.add("--portal.samanvay.token-url=http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token");
        cmd.add("--portal.samanvay.client-id=dept-" + dept + "-it");
        cmd.add("--portal.samanvay.client-secret=not-checked-by-the-stand-in");
        cmd.add("--portal.session-secret=e2e-session-secret-" + dept);
        cmd.add("--" + dept + ".login.allowed-return-uris=" + PORT.keySet().stream().filter(o -> !o.equals(dept))
                .map(o -> "http://127.0.0.1:" + PORT.get(o) + "/portal/callback").collect(java.util.stream.Collectors.joining(",")));
        PROCESSES.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
    }

    static void awaitManifest(String dept) throws Exception {
        long until = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < until) {
            try {
                var r = HTTP.send(HttpRequest.newBuilder(URI.create(base(dept) + "/.well-known/samanvay/manifest")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 || r.statusCode() == 401) { // 401 = up, and wants its discovery credential
                    return;
                }
            } catch (IOException ignored) {
                // not up yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("department " + dept + " did not start");
    }

    static String base(String dept) {
        return "http://127.0.0.1:" + PORT.get(dept);
    }

    @DynamicPropertySource
    static void worldProperties(DynamicPropertyRegistry r) {
        if (!jarsPresent) {
            return;
        }
        r.add("server.port", () -> samanvayPort);
        // REST/SOAP go over HTTPS in production; in a test runtime a source may be pointed at the plain-HTTP stand-in.
        r.add("samanvay.sources.department-service.urls.revenue-rest", () -> base("revenue"));
        r.add("samanvay.sources.department-service.urls.dbt-rest", () -> base("dbt"));
        r.add("samanvay.sources.department-service.urls.education-soap", () -> base("education"));
        // SFTP and JDBC hosts and pins are operator-configured, never taken from a manifest.
        sftpSource(r, "revenue-sftp", revenueSftp, "/outbound/712.csv", revenueFingerprint);
        sftpSource(r, "agriculture-sftp", agricultureSftp, "/outbound/crop.csv", agricultureFingerprint);
        r.add("samanvay.sources.jdbc.sources.agriculture-jdbc.mode", () -> "sandbox");
        r.add("samanvay.sources.jdbc.sources.agriculture-jdbc.jdbc-url",
                () -> "jdbc:postgresql://" + agriDb.getHost() + ":" + agriDb.getMappedPort(5432) + "/agridb");
    }

    static void sftpSource(DynamicPropertyRegistry r, String code, SshServer s, String path, String fingerprint) {
        String p = "samanvay.sources.sftp.sources." + code + ".";
        r.add(p + "mode", () -> "sandbox");
        r.add(p + "host", () -> "127.0.0.1");
        r.add(p + "port", () -> s.getPort());
        r.add(p + "remote-path", () -> path);
        r.add(p + "host-key-sha256", () -> fingerprint);
    }

    @AfterAll
    static void stopWorld() {
        PROCESSES.forEach(Process::destroyForcibly);
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
        for (SshServer s : new SshServer[] {revenueSftp, agricultureSftp}) {
            if (s != null) {
                try {
                    s.stop(true);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        if (agriDb != null) {
            agriDb.stop();
        }
        cleanCatalog();
    }

    // --- Samanvay ---------------------------------------------------------------------------------------------------
    @Autowired
    ManifestOnboarding onboarding;

    @Autowired
    ConnectorCatalog catalog;

    @Autowired
    ConnectorRuntime runtime;

    @Autowired
    IdentityLinking linking;

    @Autowired
    DepartmentLogin departmentLogin;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    com.samanvay.catalog.api.JourneyWrite journeyWrite;

    @MockitoBean
    AccessGrantVerifier grantVerifier; // the consent-grant check is not what this test is about

    static final Map<String, OnboardingResult> ONBOARDED = new LinkedHashMap<>();
    static final Map<String, String> CODE = Map.of("revenue", "REVENUE", "dbt", "DBT", "education", "EDUCATION", "agriculture", "AGRICULTURE");

    /** Every department's login is: the registered mobile number and a password, then the one-time code; and who it returns. */
    record Login(String mobile, String password, String personId) {}

    static final String ONE_TIME_CODE = "123456";
    static final Map<String, Login> LOGIN = Map.of(
            "revenue", new Login("9000000001", "asha-demo-pass", "RV-1001"),
            "dbt", new Login("9000000001", "asha-demo-pass", "DBT-1001"),
            "education", new Login("9000000001", "asha-demo-pass", "EDU-1001"),
            "agriculture", new Login("9000000001", "asha-demo-pass", "AG-1001"));

    UUID citizen;
    final Map<String, Link> links = new LinkedHashMap<>();
    final Map<String, String> assertions = new LinkedHashMap<>();

    @BeforeAll
    void onboardEveryDepartment() {
        assumeTrue(jarsPresent, "build the department jars first: scripts/build-departments.sh");
        for (String d : PORT.keySet()) {
            OnboardingPlan plan = onboarding.plan(base(d));
            assertThat(plan.documents()).as(d).allSatisfy(doc -> assertThat(doc.ready()).as(doc.category()).isTrue());
            // Samanvay accepts only signed manifests here; the admin approves each department's key (the thumbprint it was shown).
            assertThat(plan.manifestKeyThumbprint()).as(d + " signs its manifest").isNotBlank();
            ONBOARDED.put(d, onboarding.onboard(new OnboardRequest(base(d), plan.manifestDigest(),
                    plan.documents().stream().map(OnboardingPlan.DocumentPlan::category).toList(), true, Map.of(), plan.manifestKeyThumbprint())));
            assertThat(onboarding.plan(base(d)).pinnedKeyThumbprint()).as(d + " key pinned").isEqualTo(plan.manifestKeyThumbprint());
        }
        // The manifests created Education's, Revenue's and DBT's journeys as drafts; everything they need is now onboarded.
        ONBOARDED.values().forEach(r -> r.journeysCreated().forEach(journeyWrite::publishJourney));
        citizen = profiles.register(new ProfileDraft("Asha Patil", "आशा", "Asha", "Patil", "Ramesh", LocalDate.of(2007, 3, 14), "DAY", "F", "99****21"));
    }

    @Test
    void revenue_shows_its_signed_manifest_only_to_a_caller_with_its_discovery_credential() throws Exception {
        assumeTrue(jarsPresent, "build the department jars first: scripts/build-departments.sh");
        URI manifest = URI.create(base("revenue") + "/.well-known/samanvay/manifest");
        assertThat(HTTP.send(HttpRequest.newBuilder(manifest).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var shown = HTTP.send(HttpRequest.newBuilder(manifest).header("X-Discovery-Key", REVENUE_DISCOVERY_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(shown.statusCode()).isEqualTo(200);
        assertThat(shown.headers().firstValue("X-Samanvay-Signature")).isPresent();
    }

    // --- login assertion and link ---------------------------------------------------------------------------------------

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static String param(String url, String name) {
        String q = URI.create(url).getRawQuery();
        if (q == null) {
            return null;
        }
        for (String kv : q.split("&")) {
            if (kv.startsWith(name + "=")) {
                return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** The citizen's browser journey: Samanvay sends them to the department, they log in there, and come back with the assertion. */
    String loginAtDepartment(String dept, UUID who) throws Exception {
        String loginUrl = departmentLogin.startLogin(who, CODE.get(dept), returnTo(dept));
        assertThat(loginUrl).startsWith(base(dept) + "/login?");
        Login l = LOGIN.get(dept);
        String carried = "return_to=" + enc(param(loginUrl, "return_to")) + "&state=" + enc(param(loginUrl, "state")) + "&nonce=" + enc(param(loginUrl, "nonce"));
        // step one: mobile number and password; the department answers with the one-time-code page and a ticket
        HttpResponse<String> step1 = HTTP.send(HttpRequest.newBuilder(URI.create(base(dept) + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("mobile=" + enc(l.mobile()) + "&password=" + enc(l.password()) + "&" + carried)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(step1.statusCode()).as(dept + " password step").isEqualTo(200);
        java.util.regex.Matcher ticket = java.util.regex.Pattern.compile("name=\"ticket\" value=\"([^\"]+)\"").matcher(step1.body());
        assertThat(ticket.find()).as(dept + " code page carries a ticket").isTrue();
        // step two: the one-time code
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base(dept) + "/login/verify"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("ticket=" + enc(ticket.group(1)) + "&code=" + ONE_TIME_CODE + "&" + carried)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(dept + " login").isEqualTo(303);
        String location = r.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(returnTo(dept) + "?");
        return param(location, "assertion");
    }

    @Test
    @Order(1)
    void the_citizen_links_each_department_by_logging_in_at_it() throws Exception {
        for (String d : PORT.keySet()) {
            String assertion = loginAtDepartment(d, citizen);
            assertions.put(d, assertion);
            Link link = linking.assertLink(citizen, CODE.get(d), null, null, new AuthProof(LinkProofKind.DEPT_ASSERTION, assertion));
            assertThat(link.localIdToken()).as(d).isEqualTo(LOGIN.get(d).personId());
            assertThat(link.departmentCode()).isEqualTo(CODE.get(d));
            links.put(d, link);
        }
    }

    @Test
    @Order(2)
    void a_department_login_result_is_bound_to_the_citizen_who_started_it_used_once_and_one_person_links_one_citizen() throws Exception {
        UUID other = profiles.register(new ProfileDraft("Ravi Deshmukh", "रवी", "Ravi", "Deshmukh", "Mohan", LocalDate.of(2007, 8, 2), "DAY", "M", "99****22"));
        String othersAssertion = loginAtDepartment("revenue", other);

        // 1. the state was issued to `other`: a different citizen presenting this assertion is refused
        assertThatThrownBy(() -> linking.assertLink(citizen, "REVENUE", null, null, new AuthProof(LinkProofKind.DEPT_ASSERTION, othersAssertion)))
                .isInstanceOf(LinkProofInvalidException.class);

        // 2. the proof is genuine for `other`, but that Revenue person is already linked to the first citizen:
        //    one department person cannot be attached to two Samanvay citizens
        assertThatThrownBy(() -> linking.assertLink(other, "REVENUE", null, null, new AuthProof(LinkProofKind.DEPT_ASSERTION, othersAssertion)))
                .isInstanceOf(com.samanvay.identity.api.DuplicateLocalIdException.class);

        // 3. the first citizen's own, already-used assertion cannot be replayed
        assertThatThrownBy(() -> linking.assertLink(citizen, "REVENUE", null, null, new AuthProof(LinkProofKind.DEPT_ASSERTION, assertions.get("revenue"))))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    // --- fetching ----------------------------------------------------------------------------------------------------

    ConnectorResult fetch(String dept, String category) {
        String ref = ONBOARDED.get(dept).connectorRefs().stream()
                .filter(r -> catalog.byRef(r).category().code().equals(category)).findFirst().orElseThrow();
        String personId = links.get(dept).localIdToken();
        AccessGrant grant = new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(citizen), null,
                DataCategory.of(category), CODE.get(dept), ref, null, null, Instant.now(), Instant.now().plusSeconds(60), new byte[0]);
        var inputs = new ExecutionInputs(DataCategory.of(category), "wf-" + UUID.randomUUID(),
                Map.of("personId", personId, "localIdType", links.get(dept).localIdType(), "localIdToken", personId), Map.of(), Map.of());
        return runtime.execute(grant, Capability.FETCH, inputs);
    }

    JsonNode success(String dept, String category) {
        ConnectorResult r = fetch(dept, category);
        assertThat(r).as(dept + " " + category).isInstanceOf(ConnectorResult.Success.class);
        return ((ConnectorResult.Success) r).canonical();
    }

    @Test
    @Order(3)
    void revenue_over_rest_with_an_api_key_resolves_the_latest_certificate_then_fetches_it() {
        JsonNode income = success("revenue", "INCOME_CERTIFICATE");
        // RV-1001 holds two income certificates; the resolve step picks the newest (FY 2025-26, Rs 185000)
        assertThat(income.get("annualIncome").asString()).isEqualTo("185000");
        assertThat(income.get("holderName").asString()).isEqualTo("Asha Patil");
        assertThat(success("revenue", "CASTE_CERTIFICATE").get("casteCategory").asString()).isEqualTo("OBC");
        assertThat(success("revenue", "DOMICILE_CERTIFICATE").get("district").asString()).isEqualTo("Nashik");
    }

    @Test
    @Order(4)
    void revenue_over_sftp_finds_the_persons_row_in_the_departments_own_csv() {
        JsonNode land = success("revenue", "LAND_PARCEL");
        assertThat(land.get("surveyNo").asString()).isEqualTo("GAT-212/3");
        assertThat(land.get("ownerName").asString()).isEqualTo("Asha Patil");
    }

    @Test
    @Order(5)
    void dbt_over_rest_with_oauth2_client_credentials() {
        JsonNode bank = success("dbt", "BANK_ACCOUNT");
        assertThat(bank.get("accountRef").asString()).isEqualTo("XXXXXX1234");
        assertThat(bank.get("holderName").asString()).isEqualTo("Asha Patil");
    }

    @Test
    @Order(6)
    void education_over_soap_with_a_ws_security_username_token() {
        JsonNode marks = success("education", "MARKS");
        assertThat(marks.get("percentage").asString()).isEqualTo("91");
        assertThat(marks.get("board").asString()).isEqualTo("msbshse");
    }

    @Test
    @Order(7)
    void agriculture_over_jdbc_reads_only_the_published_view_with_its_read_only_account() {
        JsonNode farmer = success("agriculture", "FARMER_RECORD");
        assertThat(farmer.get("farmerName").asString()).isEqualTo("Asha Patil");
        assertThat(farmer.get("village").asString()).isEqualTo("Ojhar");
        assertThat(farmer.has("internal_notes")).isFalse();
        assertThat(farmer.toString()).doesNotContain("audit pending");
    }

    @Test
    @Order(8)
    void agriculture_over_sftp_finds_the_farmers_crop_row() {
        assertThat(success("agriculture", "CROP_RECORD").get("crop").asString()).isEqualTo("Soybean");
    }

    @Test
    @Order(9)
    void a_wrong_credential_is_refused_by_the_department_and_nothing_is_returned() {
        String good = SECRETS.get("source-revenue-rest-credential");
        try {
            SECRETS.put("source-revenue-rest-credential", "{\"X-Api-Key\":\"not-the-key\"}");
            assertThatThrownBy(() -> fetch("revenue", "INCOME_CERTIFICATE")).isInstanceOf(RuntimeException.class);
        } finally {
            SECRETS.put("source-revenue-rest-credential", good);
        }
        // and with the right credential back, the same fetch works again
        assertThat(success("revenue", "INCOME_CERTIFICATE").get("annualIncome").asString()).isEqualTo("185000");
    }

    @Test
    @Order(10)
    void a_trial_fetch_with_the_departments_own_sample_person_works_for_every_onboarded_connector() {
        for (String d : PORT.keySet()) {
            for (String ref : ONBOARDED.get(d).connectorRefs()) {
                String sample = JSON.readTree(catalog.byRef(ref).capabilitiesJson()).get("FETCH").get("sample_person_id").asString();
                assertThat(sample).as(ref).isEqualTo(LOGIN.get(d).personId());
                var result = runtime.trial(ref, sample, new com.samanvay.shared.PrincipalRef(com.samanvay.shared.PrincipalRef.Kind.ADMIN, "admin-e2e"));
                assertThat(result).as(d + " " + ref).isInstanceOf(ConnectorResult.Success.class);
            }
        }
    }

    @Test
    @Order(11)
    void dbt_receives_the_person_id_in_a_post_body_not_in_the_url() {
        // DBT no longer serves the id in a query string: the only way the earlier bank fetch can have worked is the POST body
        var connector = catalog.byRef(ONBOARDED.get("dbt").connectorRefs().get(0));
        JsonNode fetch = JSON.readTree(connector.capabilitiesJson()).get("FETCH");
        assertThat(fetch.get("method").asString()).isEqualTo("POST");
        assertThat(fetch.get("body_inputs").asString()).isEqualTo("dbtId");
        assertThat(success("dbt", "BANK_ACCOUNT").get("accountRef").asString()).isEqualTo("XXXXXX1234");
    }


    // --- the four journeys, run from the departments' own portals ----------------------------------------------------------

    record Reply(int status, String body, java.net.http.HttpHeaders headers) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    Reply http(String method, String url, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) {
            b.header("Cookie", "dept_session=" + cookie);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r.headers());
    }

    Reply portal(String dept, String method, String path, String cookie, String body) throws Exception {
        return http(method, base(dept) + path, cookie, body);
    }

    static String sessionCookie(Reply r) {
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    /** Signs in on the department's own portal (password, then the one-time code) and returns the session cookie. */
    String portalSignIn(String dept, Login l) throws Exception {
        Reply step1 = portal(dept, "POST", "/portal-api/sign-in", null, "{\"mobile\":\"" + l.mobile() + "\",\"password\":\"" + l.password() + "\"}");
        assertThat(step1.status()).as(dept + " portal sign in, password step").isEqualTo(200);
        Reply step2 = portal(dept, "POST", "/portal-api/verify", null,
                "{\"ticket\":\"" + step1.json().get("ticket").asString() + "\",\"code\":\"" + ONE_TIME_CODE + "\"}");
        assertThat(step2.status()).as(dept + " portal sign in, code step").isEqualTo(200);
        return sessionCookie(step2);
    }

    /** The citizen's browser: the other department's login page, then back to the portal's callback. Returns the callback's reply. */
    Reply connect(String home, String cookie, String journey, String other, Login l) throws Exception {
        Reply start = portal(home, "POST", "/portal-api/journeys/" + journey + "/links/" + CODE.get(other), cookie, "{}");
        assertThat(start.status()).as(home + " starts linking " + other).isEqualTo(200);
        String loginUrl = start.json().get("loginUrl").asString();
        assertThat(loginUrl).startsWith(base(other) + "/login?");
        String carried = "return_to=" + enc(param(loginUrl, "return_to")) + "&state=" + enc(param(loginUrl, "state")) + "&nonce=" + enc(param(loginUrl, "nonce"));
        HttpResponse<String> pw = HTTP.send(HttpRequest.newBuilder(URI.create(base(other) + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("mobile=" + enc(l.mobile()) + "&password=" + enc(l.password()) + "&" + carried)).build(),
                HttpResponse.BodyHandlers.ofString());
        java.util.regex.Matcher ticket = java.util.regex.Pattern.compile("name=\"ticket\" value=\"([^\"]+)\"").matcher(pw.body());
        assertThat(ticket.find()).as(other + " code page").isTrue();
        HttpResponse<String> done = HTTP.send(HttpRequest.newBuilder(URI.create(base(other) + "/login/verify"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("ticket=" + enc(ticket.group(1)) + "&code=" + ONE_TIME_CODE + "&" + carried)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(done.statusCode()).as(other + " login").isEqualTo(303);
        String back = done.headers().firstValue("Location").orElseThrow();
        assertThat(back).startsWith(base(home) + "/portal/callback?");
        return http("GET", back, cookie, null);
    }

    static String submissionFor(JsonNode definition) {
        StringBuilder sb = new StringBuilder("{\"submission\":{");
        boolean first = true;
        for (JsonNode f : definition.get("form")) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            String value = f.get("options").isEmpty() ? "Test value" : f.get("options").get(0).asString();
            sb.append('"').append(f.get("name").asString()).append("\":\"").append(value).append('"');
        }
        return sb.append("}}").toString();
    }

    @Test
    @Order(20)
    void every_journey_runs_on_its_own_departments_portal_from_sign_in_to_tracking() throws Exception {
        assumeTrue(jarsPresent, "build the department jars first: scripts/build-departments.sh");
        Login asha = new Login("9000000001", "asha-demo-pass", null);
        for (String home : PORT.keySet()) {
            String cookie = portalSignIn(home, asha);
            assertThat(portal(home, "GET", "/portal-api/me", cookie, null).json().get("department").asString()).isEqualTo(CODE.get(home));

            JsonNode journeys = portal(home, "GET", "/portal-api/journeys", cookie, null).json();
            assertThat(journeys).as(home + " offers a journey").isNotEmpty();
            String code = journeys.get(0).get("code").asString();

            // the manifest promises exactly this journey, and the portal serves it
            JsonNode manifestJourneys = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base(home) + "/.well-known/samanvay/manifest"))
                    .header("X-Discovery-Key", REVENUE_DISCOVERY_KEY).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).get("journeys");
            assertThat(manifestJourneys).as(home + " manifest journeys").isNotNull();

            // every other department the journey needs: log in there, come back, the link is saved
            JsonNode before = portal(home, "GET", "/portal-api/journeys/" + code + "/readiness", cookie, null).json();
            for (JsonNode need : before.get("departments")) {
                String other = need.get("departmentCode").asString().toLowerCase();
                if (!other.equals(home) && !need.get("linked").asBoolean()) {
                    Reply back = connect(home, cookie, code, other, asha);
                    assertThat(back.status()).as(home + " callback from " + other).isEqualTo(302);
                    assertThat(back.headers().firstValue("Location").orElse("")).contains("linked=" + CODE.get(other));
                }
            }
            JsonNode ready = portal(home, "GET", "/portal-api/journeys/" + code + "/readiness", cookie, null).json();
            assertThat(ready.get("departments")).as(home + " all connected").allSatisfy(d -> assertThat(d.get("linked").asBoolean()).isTrue());

            // consent: the exact wording, a wrong code refused, the right code grants a department-signed consent
            Reply wording = portal(home, "GET", "/portal-api/journeys/" + code + "/consent", cookie, null);
            assertThat(wording.status()).as(home + " consent wording").isEqualTo(200);
            assertThat(wording.json().get("purposeText").asString()).isNotBlank();
            String requestId = wording.json().get("requestId").asString();
            assertThat(portal(home, "POST", "/portal-api/journeys/" + code + "/consent", cookie,
                    "{\"requestId\":\"" + requestId + "\",\"code\":\"000000\"}").status()).isEqualTo(401);
            assertThat(portal(home, "POST", "/portal-api/journeys/" + code + "/consent", cookie,
                    "{\"requestId\":\"" + requestId + "\",\"code\":\"" + ONE_TIME_CODE + "\"}").status()).as(home + " consent").isEqualTo(200);
            assertThat(portal(home, "GET", "/portal-api/journeys/" + code + "/readiness", cookie, null).json().get("consentActive").asBoolean()).isTrue();

            // apply, then track
            JsonNode definition = portal(home, "GET", "/portal-api/journeys/" + code, cookie, null).json();
            Reply submitted = portal(home, "POST", "/portal-api/journeys/" + code + "/submit", cookie, submissionFor(definition));
            assertThat(submitted.status()).as(home + " submit: " + submitted.body()).isEqualTo(200);
            String reference = null;
            for (int i = 0; i < 100 && reference == null; i++) {
                for (JsonNode app : portal(home, "GET", "/portal-api/applications", cookie, null).json()) {
                    if (code.equals(app.get("journeyCode").asString())) {
                        reference = app.get("referenceNo").asString();
                    }
                }
                if (reference == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(reference).as(home + " application appears").isNotNull();
            assertThat(portal(home, "GET", "/portal-api/applications/" + reference, cookie, null).status()).isEqualTo(200);
            assertThat(portal(home, "GET", "/portal-api/applications/" + reference + "/steps", cookie, null).status()).isEqualTo(200);
            assertThat(portal(home, "GET", "/portal-api/applications/" + reference + "/records", cookie, null).status()).isEqualTo(200);
        }
    }

    @Test
    @Order(21)
    void a_person_who_starts_at_two_departments_is_one_citizen_after_linking_them() throws Exception {
        assumeTrue(jarsPresent, "build the department jars first: scripts/build-departments.sh");
        Login ravi = new Login("9000000002", "ravi-demo-pass", null);
        String atEducation = portalSignIn("education", ravi); // makes the citizen "Ravi at Education"
        String atDbt = portalSignIn("dbt", ravi); // makes a second, empty record "Ravi at DBT"
        // DBT now links Education: the same person proved both in one session, so the two records become one
        Reply back = connect("dbt", atDbt, "DBT_ACCOUNT_SEEDING", "education", ravi);
        assertThat(back.status()).isEqualTo(302);
        assertThat(back.headers().firstValue("Set-Cookie")).as("a new session for the surviving record").isPresent();
        String merged = sessionCookie(back);
        assertThat(portal("dbt", "GET", "/portal-api/journeys/DBT_ACCOUNT_SEEDING/readiness", merged, null).status()).isEqualTo(200);
        // signing in again at either department finds the same citizen
        assertThat(portal("education", "GET", "/portal-api/me", atEducation, null).status()).isEqualTo(200);
    }

    // --- housekeeping: leave the shared database as we found it --------------------------------------------------------

    static void cleanCatalog() {
        try (Connection c = DriverManager.getConnection(PostgresContainerSupport.POSTGRES.getJdbcUrl(), PostgresContainerSupport.POSTGRES.getUsername(),
                PostgresContainerSupport.POSTGRES.getPassword())) {
            for (OnboardingResult r : ONBOARDED.values()) {
                for (String ref : r.connectorRefs()) {
                    exec(c, "DELETE FROM catalog_mapping WHERE connector_ref = ?", ref);
                }
                for (String ref : r.connectorRefs()) {
                    exec(c, "DELETE FROM catalog_connector WHERE ref = ?", ref);
                }
                for (String code : r.journeysCreated()) {
                    exec(c, "DELETE FROM catalog_journey WHERE code = ?", code);
                }
                for (String code : r.dataSources()) {
                    exec(c, "DELETE FROM catalog_data_source WHERE code = ?", code);
                }
                exec(c, "UPDATE catalog_department SET identity_spec = '{}'::jsonb, manifest_digest = NULL WHERE code = ?", r.departmentCode());
            }
        } catch (Exception e) {
            // best effort: the test's own assertions already ran
        }
    }

    static void exec(Connection c, String sql, String arg) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }
}
