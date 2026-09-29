package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.sftp.SftpCsvClient;
import com.samanvay.connector.internal.source.sftp.SftpSourceProperties;
import com.samanvay.connector.internal.source.sftp.SftpTransportException;
import com.samanvay.shared.SecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real SFTP transport, end to end against an in-process Apache MINA SshServer on 127.0.0.1
 * (random port, SFTP subsystem, virtual file system rooted at a temp dir, password
 * authenticator). Throwaway credentials; no Docker.
 */
class SftpCsvRealTransportTest {

    static final String SOURCE = "municipal-sftp-real";
    static final String USER = "fixture-user";
    static final String PASSWORD = "fixture-password-Zx81";
    static final String CSV = "propertyId,propertyRef,ward\nPROP-1,WARD-01-1,Ward 1\nPROP-88,WARD-12-88,Ward 12\n";

    @TempDir
    Path root;

    SshServer server;
    String fingerprint;
    final AtomicInteger authAttempts = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        Files.createDirectories(root.resolve("outbound"));
        Files.writeString(root.resolve("outbound/property.csv"), CSV, StandardCharsets.UTF_8);

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, session) -> {
            authAttempts.incrementAndGet();
            return USER.equals(user) && PASSWORD.equals(password);
        });
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();

        KeyPair hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
        fingerprint = KeyUtils.getFingerPrint(hostKey.getPublic());
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    /** Holds one credential; refuses resolve() (which may generate), like real source credentials. */
    record FixedSecrets(Map<String, String> values) implements SecretStore {
        @Override
        public Secret resolve(String key) {
            throw new AssertionError("source credentials must use find()");
        }

        @Override
        public Optional<Secret> find(String key) {
            return Optional.ofNullable(values.get(key)).map(v -> new Secret(v.getBytes(StandardCharsets.UTF_8)));
        }
    }

    SftpSourceProperties.Source source(String hostKey) {
        return new SftpSourceProperties.Source(
                SourceMode.LIVE, "127.0.0.1", server.getPort(), "/outbound/property.csv", hostKey,
                Duration.ofSeconds(5), Duration.ofSeconds(10), null);
    }

    SftpCsvAdapter adapter(String hostKey, String credential) {
        Map<String, String> secrets = new HashMap<>();
        if (credential != null) {
            secrets.put(SourceCredentials.secretKey(SOURCE), credential);
        }
        var client = new SftpCsvClient(
                new SftpSourceProperties(Map.of(SOURCE, source(hostKey))), new SourceCredentials(new FixedSecrets(secrets)));
        return new SftpCsvAdapter(new MockSftpStore(), client);
    }

    static AdapterRequest request(String source, String host, String id) {
        return new AdapterRequest(source, "SFTP_CSV", host, "/ignored-when-configured.csv", null, Map.of("propertyId", id), "secret:none");
    }

    @Test
    void downloads_the_csv_over_real_sftp_and_returns_the_matching_row() {
        AdapterResponse response = adapter(fingerprint, USER + ":" + PASSWORD).execute(request(SOURCE, "sftp.invalid", "PROP-88"));

        assertThat(response.body().get("propertyId").asString()).isEqualTo("PROP-88");
        assertThat(response.body().get("propertyRef").asString()).isEqualTo("WARD-12-88");
        assertThat(response.body().get("ward").asString()).isEqualTo("Ward 12");
        assertThat(authAttempts.get()).isEqualTo(1);
    }

    @Test
    void unknown_id_returns_the_same_unknown_shape_as_the_mock_path() {
        AdapterResponse response = adapter(fingerprint, USER + ":" + PASSWORD).execute(request(SOURCE, "sftp.invalid", "PROP-404"));

        assertThat(response.body().get("propertyRef").asString()).isEqualTo("UNKNOWN");
    }

    @Test
    void wrong_password_is_refused_without_echoing_it() {
        SftpCsvAdapter adapter = adapter(fingerprint, USER + ":not-the-password");

        assertThatThrownBy(() -> adapter.execute(request(SOURCE, "sftp.invalid", "PROP-88")))
                .isInstanceOf(SftpTransportException.class)
                .hasMessageContaining(SOURCE)
                .hasMessageNotContaining("not-the-password");
        assertThat(authAttempts.get()).as("the server actually saw and rejected the password").isPositive();
    }

    @Test
    void server_with_an_unexpected_host_key_is_refused_before_any_password_is_sent() {
        SftpCsvAdapter adapter = adapter("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", USER + ":" + PASSWORD);

        assertThatThrownBy(() -> adapter.execute(request(SOURCE, "sftp.invalid", "PROP-88")))
                .isInstanceOf(SftpTransportException.class);
        assertThat(authAttempts.get()).isZero();
    }

    @Test
    void missing_credential_fails_naming_the_secret_key_only() {
        SftpCsvAdapter adapter = adapter(fingerprint, null);

        assertThatThrownBy(() -> adapter.execute(request(SOURCE, "sftp.invalid", "PROP-88")))
                .isInstanceOf(SftpTransportException.class)
                .hasMessageContaining(SourceCredentials.secretKey(SOURCE));
        assertThat(authAttempts.get()).isZero();
    }

    @Test
    void missing_remote_file_fails_as_a_transport_error() {
        var client = new SftpCsvClient(
                new SftpSourceProperties(Map.of(SOURCE, new SftpSourceProperties.Source(
                        SourceMode.LIVE, "127.0.0.1", server.getPort(), "/outbound/nope.csv", fingerprint, null, null, null))),
                new SourceCredentials(new FixedSecrets(Map.of(SourceCredentials.secretKey(SOURCE), USER + ":" + PASSWORD))));

        assertThatThrownBy(() -> client.download(SOURCE, "secret:none", null, null)).isInstanceOf(SftpTransportException.class);
    }

    @Test
    void oversized_file_is_refused() {
        var client = new SftpCsvClient(
                new SftpSourceProperties(Map.of(SOURCE, new SftpSourceProperties.Source(
                        SourceMode.LIVE, "127.0.0.1", server.getPort(), "/outbound/property.csv", fingerprint, null, null, 10L))),
                new SourceCredentials(new FixedSecrets(Map.of(SourceCredentials.secretKey(SOURCE), USER + ":" + PASSWORD))));

        assertThatThrownBy(() -> client.download(SOURCE, "secret:none", null, null))
                .isInstanceOf(SftpTransportException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void authConfigRef_can_name_another_sources_credential() {
        String other = "shared-sftp-credential-holder";
        var client = new SftpCsvClient(
                new SftpSourceProperties(Map.of(SOURCE, source(fingerprint))),
                new SourceCredentials(new FixedSecrets(Map.of(SourceCredentials.secretKey(other), USER + ":" + PASSWORD))));

        assertThat(client.download(SOURCE, "secret:" + other, null, null)).isEqualTo(CSV);
    }

    @Test
    void simulator_host_still_reads_the_in_memory_store_and_never_touches_sftp() {
        // Client with no configured sources: any real call would throw.
        var client = new SftpCsvClient(new SftpSourceProperties(Map.of()), new SourceCredentials(new FixedSecrets(Map.of())));
        var adapter = new SftpCsvAdapter(new MockSftpStore(), client);

        AdapterResponse response = adapter.execute(request("municipal-sftp-mock", MockDepartmentBackend.HOST, "PROP-88"));

        assertThat(response.body().get("propertyRef").asString()).isEqualTo("WARD-12-88");
        assertThat(authAttempts.get()).isZero();
    }
}
