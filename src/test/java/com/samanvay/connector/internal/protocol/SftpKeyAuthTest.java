package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
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
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SFTP login with an SSH private key (no password), against a server that accepts only that one public key. */
class SftpKeyAuthTest {

    static final String SOURCE = "dept-sftp";
    static final String CSV = "personId,crop\nAG-1001,Soybean\n";

    @TempDir
    Path root;

    SshServer server;
    String fingerprint;
    KeyPair authorised;

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(root.resolve("outbound"));
        Files.writeString(root.resolve("outbound/crop.csv"), CSV, StandardCharsets.UTF_8);
        authorised = rsa();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((u, p, s) -> false); // passwords are not accepted at all
        server.setPublickeyAuthenticator((u, key, s) -> "agri".equals(u) && KeyUtils.compareKeys(key, authorised.getPublic()));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        fingerprint = KeyUtils.getFingerPrint(server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(true);
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** PKCS#8 PEM, the standard unencrypted private-key text format. */
    static String pem(KeyPair k) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(k.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    /** OpenSSH-format private key protected by a passphrase (bcrypt KDF + AES), written by sshd's own writer. */
    static String encryptedPem(KeyPair k, String passphrase) throws Exception {
        var ctx = new OpenSSHKeyEncryptionContext();
        ctx.setPassword(passphrase);
        ctx.setCipherName("AES");
        ctx.setCipherType("256");
        ctx.setCipherMode("CTR");
        var out = new java.io.ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(k, "test", ctx, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    static String jsonWithPassphrase(String user, String privateKey, String passphrase) {
        return json(user, privateKey).replace("}", ",\"passphrase\":\"" + passphrase + "\"}");
    }

    static String json(String user, String privateKey) {
        return "{\"username\":\"" + user + "\",\"private_key\":\"" + privateKey.replace("\n", "\\n") + "\"}";
    }

    SftpCsvAdapter adapter(String secret, String pin) {
        SecretStore store = k -> k.equals(SourceCredentials.secretKey(SOURCE)) && secret != null ? new SecretStore.Secret(secret.getBytes(StandardCharsets.UTF_8)) : null;
        var source = new SftpSourceProperties.Source(SourceMode.LIVE, "127.0.0.1", server.getPort(), "/outbound/crop.csv", pin,
                Duration.ofSeconds(5), Duration.ofSeconds(10), null);
        return new SftpCsvAdapter(new MockSftpStore(), new SftpCsvClient(new SftpSourceProperties(Map.of(SOURCE, source)), new SourceCredentials(store)));
    }

    AdapterRequest req() {
        return new AdapterRequest(SOURCE, "SFTP_CSV", "sftp.invalid", "/outbound/crop.csv", null, Map.of("personId", "AG-1001"), "secret:none", null, null,
                Map.of("key_column", "personId"));
    }

    @Test
    void a_private_key_logs_in_where_no_password_is_accepted() throws Exception {
        var r = adapter(json("agri", pem(authorised)), fingerprint).execute(req());
        assertThat(r.body().get("crop").asString()).isEqualTo("Soybean");
    }

    @Test
    void a_different_key_is_refused_and_the_error_never_contains_key_material() throws Exception {
        String wrong = pem(rsa());
        assertThatThrownBy(() -> adapter(json("agri", wrong), fingerprint).execute(req()))
                .isInstanceOf(SftpTransportException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("PRIVATE KEY").doesNotContain(wrong.substring(40, 80)));
    }

    @Test
    void the_wrong_user_with_the_right_key_is_refused() throws Exception {
        assertThatThrownBy(() -> adapter(json("someone-else", pem(authorised)), fingerprint).execute(req())).isInstanceOf(SftpTransportException.class);
    }

    @Test
    void the_server_host_key_pin_is_still_enforced_with_key_login() throws Exception {
        assertThatThrownBy(() -> adapter(json("agri", pem(authorised)), "SHA256:" + "A".repeat(43)).execute(req())).isInstanceOf(SftpTransportException.class);
    }

    @Test
    void a_key_credential_with_no_username_or_an_unreadable_key_is_a_clear_configuration_error() throws Exception {
        assertThatThrownBy(() -> adapter("{\"private_key\":\"x\"}", fingerprint).execute(req())).isInstanceOf(SftpTransportException.class).hasMessageContaining("username");
        assertThatThrownBy(() -> adapter(json("agri", "not a key"), fingerprint).execute(req())).isInstanceOf(SftpTransportException.class);
    }

    @Test
    void a_password_credential_is_refused_by_a_key_only_server_proving_the_two_paths_are_distinct() throws Exception {
        assertThatThrownBy(() -> adapter("agri:secret", fingerprint).execute(req())).isInstanceOf(SftpTransportException.class);
    }

    @Test
    void a_passphrase_protected_key_logs_in_when_the_credential_carries_the_passphrase() throws Exception {
        String protectedKey = encryptedPem(authorised, "correct horse");
        var r = adapter(jsonWithPassphrase("agri", protectedKey, "correct horse"), fingerprint).execute(req());
        assertThat(r.body().get("crop").asString()).isEqualTo("Soybean");
    }

    @Test
    void a_passphrase_protected_key_with_a_wrong_or_missing_passphrase_is_refused_without_leaking_it() throws Exception {
        String protectedKey = encryptedPem(authorised, "correct horse");
        for (String secret : new String[] {jsonWithPassphrase("agri", protectedKey, "wrong battery"), json("agri", protectedKey)}) {
            assertThatThrownBy(() -> adapter(secret, fingerprint).execute(req()))
                    .isInstanceOf(SftpTransportException.class)
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("correct horse").doesNotContain("wrong battery").doesNotContain("PRIVATE KEY"));
        }
    }
}
