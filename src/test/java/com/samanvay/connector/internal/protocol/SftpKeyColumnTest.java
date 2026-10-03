package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.sftp.SftpCsvClient;
import com.samanvay.connector.internal.source.sftp.SftpSourceProperties;
import com.samanvay.shared.SecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A department's CSV is keyed by its own column (manifest access.sftp.keyColumn), not a hardcoded one. */
class SftpKeyColumnTest {

    static final String SOURCE = "revenue-712-sftp";
    static final String USER = "revenue-sftp";
    static final String PASSWORD = "rev-sftp-Zx81";
    static final String CSV = "personId,surveyNo,village,taluka,district,areaHectares,ownerName\n"
            + "RV-1001,GAT-212/3,Ojhar,Nashik,Nashik,1.85,Asha Patil\nRV-1002,SN-47/1,Loni,Haveli,Pune,0.92,Ravi Deshmukh\n";

    @TempDir
    Path root;

    SshServer server;
    String fingerprint;

    @BeforeEach
    void startServer() throws Exception {
        Files.createDirectories(root.resolve("outbound"));
        Files.writeString(root.resolve("outbound/712.csv"), CSV, StandardCharsets.UTF_8);
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        KeyPair hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
        fingerprint = KeyUtils.getFingerPrint(hostKey.getPublic());
    }

    @AfterEach
    void stopServer() throws IOException {
        server.stop(true);
    }

    SftpCsvAdapter adapter() {
        SecretStore store = k -> k.equals(SourceCredentials.secretKey(SOURCE))
                ? new SecretStore.Secret((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)) : null;
        var source = new SftpSourceProperties.Source(SourceMode.LIVE, "127.0.0.1", server.getPort(), "/outbound/712.csv", fingerprint,
                Duration.ofSeconds(5), Duration.ofSeconds(10), null);
        return new SftpCsvAdapter(new MockSftpStore(), new SftpCsvClient(new SftpSourceProperties(Map.of(SOURCE, source)), new SourceCredentials(store)));
    }

    AdapterRequest req(String personId, Map<String, String> access) {
        return new AdapterRequest(SOURCE, "SFTP_CSV", "sftp.invalid", "/outbound/712.csv", null, Map.of("personId", personId), "secret:none",
                null, null, access);
    }

    @Test
    void the_row_is_found_by_the_declared_key_column() {
        AdapterResponse r = adapter().execute(req("RV-1002", Map.of("key_column", "personId")));
        assertThat(r.body().get("ownerName").asString()).isEqualTo("Ravi Deshmukh");
        assertThat(r.body().get("surveyNo").asString()).isEqualTo("SN-47/1");
        assertThat(r.body().get("village").asString()).isEqualTo("Loni");
    }

    @Test
    void an_unknown_key_does_not_return_another_persons_row() {
        AdapterResponse r = adapter().execute(req("RV-9999", Map.of("key_column", "personId")));
        assertThat(r.body().has("ownerName")).isFalse();
        assertThat(r.body().toString()).doesNotContain("Asha").doesNotContain("Ravi");
    }

    @Test
    void without_a_declared_key_column_the_original_property_id_column_is_used() {
        // 712.csv has no propertyId column, so nothing matches: the old default is unchanged, not guessed.
        AdapterResponse r = adapter().execute(req("RV-1001", Map.of()));
        assertThat(r.body().has("ownerName")).isFalse();
    }
}
