package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two more ways a department can ask Samanvay to prove itself: a signed request (HMAC-SHA256 over method, path+query, time
 * and body hash) and a client certificate (mutual TLS). The signature is re-computed here independently from the written
 * spec (docs/contracts/hmac-request-signing.md), and the TLS tests use a real HTTPS server that demands a client certificate.
 */
class RestAuthSigningAndMtlsTest {

    static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

    record Seen(String method, String rawUri, Map<String, String> headers, String body, String clientCn) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    HttpServer plain;
    HttpsServer tls;

    @AfterEach
    void down() {
        if (plain != null) {
            plain.stop(0);
        }
        if (tls != null) {
            tls.stop(0);
        }
    }

    // --- HMAC ---------------------------------------------------------------------------------------------------

    static final String HMAC_SPEC = "{\"scheme\":\"HMAC_SHA256\",\"parameters\":[{\"name\":\"key_id\",\"in\":\"header\"},{\"name\":\"secret\",\"in\":\"signature\",\"secret\":true}]}";

    int startPlain() throws Exception {
        plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plain.createContext("/", ex -> {
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                    h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), null));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        plain.start();
        return plain.getAddress().getPort();
    }

    static SourceCredentials creds(Map<String, String> secrets) {
        SecretStore store = k -> secrets.containsKey(k) ? new SecretStore.Secret(secrets.get(k).getBytes(StandardCharsets.UTF_8)) : null;
        return new SourceCredentials(store);
    }

    RestAdapter signingAdapter(Map<String, String> secrets, Instant now) {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new RestAdapter(new MockDepartmentBackend(), http, "http", DepartmentServiceOverrides.NONE,
                new RestAuth(creds(secrets), http, "http", Clock.fixed(now, ZoneOffset.UTC)));
    }

    AdapterRequest req(int port, String endpoint, Map<String, String> inputs, Map<String, String> access, String authType, String spec) {
        return new AdapterRequest("agri", "REST", "127.0.0.1:" + port, endpoint, null, inputs, "secret:agri", authType, spec, access);
    }

    /** The spec, written out independently of the production code. */
    static String expected(String secret, String method, String pathAndQuery, long ts, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = method + "\n" + pathAndQuery + "\n" + ts + "\n" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_get_is_signed_over_method_path_query_time_and_the_empty_body() throws Exception {
        int port = startPlain();
        signingAdapter(Map.of("source-agri-credential", "{\"key_id\":\"samanvay\",\"secret\":\"s3cret-key\"}"), T)
                .execute(req(port, "/v1/farmer/{id}", Map.of("id", "AG 1", "lang", "mr"), Map.of(), "HMAC_SHA256", HMAC_SPEC));
        Seen s = seen.get(0);
        assertThat(s.headers()).containsEntry("x-key-id", "samanvay").containsEntry("x-timestamp", String.valueOf(T.getEpochSecond()));
        assertThat(s.rawUri()).isEqualTo("/v1/farmer/AG%201?lang=mr");
        assertThat(s.headers().get("x-signature")).isEqualTo(expected("s3cret-key", "GET", s.rawUri(), T.getEpochSecond(), ""));
    }

    @Test
    void a_post_signature_covers_the_body_so_a_changed_body_no_longer_matches() throws Exception {
        int port = startPlain();
        signingAdapter(Map.of("source-agri-credential", "{\"key_id\":\"k\",\"secret\":\"abc\"}"), T)
                .execute(req(port, "/v1/bank", Map.of("dbtId", "D1"), Map.of("method", "POST", "body_inputs", "dbtId"), "HMAC_SHA256", HMAC_SPEC));
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.headers().get("x-signature")).isEqualTo(expected("abc", "POST", "/v1/bank", T.getEpochSecond(), s.body()));
        assertThat(s.headers().get("x-signature")).isNotEqualTo(expected("abc", "POST", "/v1/bank", T.getEpochSecond(), "{\"dbtId\":\"D2\"}"));
    }

    @Test
    void the_signature_changes_with_the_time_and_the_secret_never_travels() throws Exception {
        int port = startPlain();
        Map<String, String> secret = Map.of("source-agri-credential", "{\"key_id\":\"k\",\"secret\":\"very-secret-value\"}");
        signingAdapter(secret, T).execute(req(port, "/v1/x", Map.of(), Map.of(), "HMAC_SHA256", HMAC_SPEC));
        signingAdapter(secret, T.plusSeconds(60)).execute(req(port, "/v1/x", Map.of(), Map.of(), "HMAC_SHA256", HMAC_SPEC));
        assertThat(seen.get(0).headers().get("x-signature")).isNotEqualTo(seen.get(1).headers().get("x-signature"));
        assertThat(seen.toString()).doesNotContain("very-secret-value");
    }

    @Test
    void a_missing_key_id_or_secret_is_a_clear_error_and_no_call_is_made() throws Exception {
        int port = startPlain();
        assertThatThrownBy(() -> signingAdapter(Map.of("source-agri-credential", "{\"key_id\":\"k\"}"), T)
                .execute(req(port, "/v1/x", Map.of(), Map.of(), "HMAC_SHA256", HMAC_SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("secret");
        assertThatThrownBy(() -> signingAdapter(Map.of(), T).execute(req(port, "/v1/x", Map.of(), Map.of(), "HMAC_SHA256", HMAC_SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class);
        assertThat(seen).isEmpty();
    }

    // --- mutual TLS -----------------------------------------------------------------------------------------------

    static void keytool(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    static void identity(Path dir, String name, String san) throws Exception {
        keytool(dir, "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=" + name, "-ext", san,
                "-keystore", name + ".p12", "-storetype", "PKCS12", "-storepass", "changeit");
        keytool(dir, "-exportcert", "-rfc", "-alias", name, "-keystore", name + ".p12", "-storepass", "changeit", "-file", name + ".pem");
    }

    static KeyStore load(Path file) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        return ks;
    }

    record Pki(Path dir, String serverPem, String clientP12Base64, String strangerP12Base64) {}

    @TempDir
    Path dir;

    Pki pki() throws Exception {
        identity(dir, "server", "san=ip:127.0.0.1");
        identity(dir, "client", "san=dns:client");
        identity(dir, "stranger", "san=dns:stranger");
        // the server trusts exactly one client certificate
        keytool(dir, "-importcert", "-noprompt", "-alias", "client", "-file", "client.pem", "-keystore", "trust.p12", "-storetype", "PKCS12", "-storepass", "changeit");
        return new Pki(dir, Files.readString(dir.resolve("server.pem")), Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve("client.p12"))),
                Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve("stranger.p12"))));
    }

    int startTls(Pki pki) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(pki.dir().resolve("server.p12")), "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load(pki.dir().resolve("trust.p12")));
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        tls = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tls.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                SSLContext c = getSSLContext();
                javax.net.ssl.SSLParameters sp = c.getDefaultSSLParameters();
                sp.setNeedClientAuth(true);
                params.setSSLParameters(sp);
            }
        });
        tls.createContext("/", ex -> {
            String cn = "";
            try {
                cn = ((HttpsExchange) ex).getSSLSession().getPeerPrincipal().getName();
            } catch (Exception ignored) {
                // no client certificate
            }
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), Map.of(), "", cn));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        tls.start();
        return tls.getAddress().getPort();
    }

    static final String MTLS_SPEC = "{\"scheme\":\"MTLS\",\"parameters\":[{\"name\":\"client_certificate\",\"in\":\"tls\",\"secret\":true},"
            + "{\"name\":\"client_certificate_password\",\"in\":\"tls\",\"secret\":true},{\"name\":\"server_ca\",\"in\":\"tls\"}]}";

    RestAdapter tlsAdapter(Map<String, String> secrets) {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(3), Duration.ofSeconds(8));
        return new RestAdapter(new MockDepartmentBackend(), http, "https", DepartmentServiceOverrides.NONE, new RestAuth(creds(secrets), http, "https"));
    }

    static String json(String p12, String serverPem, String password) {
        return "{\"client_certificate\":\"" + p12 + "\",\"client_certificate_password\":\"" + password + "\",\"server_ca\":\"" + serverPem.replace("\r", "").replace("\n", "\\n") + "\"}";
    }

    @Test
    void the_client_certificate_is_presented_and_the_server_sees_who_called() throws Exception {
        Pki pki = pki();
        int port = startTls(pki);
        var r = tlsAdapter(Map.of("source-agri-credential", json(pki.clientP12Base64(), pki.serverPem(), "changeit")))
                .execute(req(port, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC));
        assertThat(r.body().get("ok").asBoolean()).isTrue();
        assertThat(seen.get(0).clientCn()).contains("CN=client");
    }

    @Test
    void a_certificate_the_server_does_not_trust_fails_the_handshake() throws Exception {
        Pki pki = pki();
        int port = startTls(pki);
        assertThatThrownBy(() -> tlsAdapter(Map.of("source-agri-credential", json(pki.strangerP12Base64(), pki.serverPem(), "changeit")))
                .execute(req(port, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC))).isInstanceOf(RuntimeException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void a_server_whose_certificate_is_not_trusted_is_refused_by_the_client() throws Exception {
        Pki pki = pki();
        int port = startTls(pki);
        // no server_ca given: the self-signed server certificate is not in the JVM's trust store
        String noCa = "{\"client_certificate\":\"" + pki.clientP12Base64() + "\",\"client_certificate_password\":\"changeit\"}";
        assertThatThrownBy(() -> tlsAdapter(Map.of("source-agri-credential", noCa)).execute(req(port, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC)))
                .isInstanceOf(RuntimeException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void a_wrong_password_or_a_garbled_certificate_is_a_clear_error_that_never_echoes_the_secret() throws Exception {
        Pki pki = pki();
        int port = startTls(pki);
        assertThatThrownBy(() -> tlsAdapter(Map.of("source-agri-credential", json(pki.clientP12Base64(), pki.serverPem(), "wrong-password")))
                .execute(req(port, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).satisfies(e -> assertThat(e.getMessage()).doesNotContain("wrong-password"));
        assertThatThrownBy(() -> tlsAdapter(Map.of("source-agri-credential", json("bm90LWEtY2VydA==", pki.serverPem(), "changeit")))
                .execute(req(port, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC))).isInstanceOf(IllegalConnectorConfigurationException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void a_missing_certificate_credential_names_the_source_and_parameter() throws Exception {
        assertThatThrownBy(() -> tlsAdapter(Map.of()).execute(req(1, "/v1/x", Map.of(), Map.of(), "MTLS", MTLS_SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("agri").hasMessageContaining("client_certificate");
    }
}
