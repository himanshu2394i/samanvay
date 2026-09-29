package com.samanvay.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSecretStoreTest {

    @TempDir
    Path dir;

    private void provision(String key, byte[] value) throws Exception {
        Files.writeString(dir.resolve(key), Base64.getEncoder().encodeToString(value) + "\n");
    }

    @Test
    void readsProvisionedSecretsAsBase64FilesNamedByKey() throws Exception {
        byte[] privateKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
        provision("audit-checkpoint-signing-key", privateKey);
        provision("source-ifsc-bank-credential", "kid:s3cret".getBytes(StandardCharsets.UTF_8));
        FileSecretStore store = new FileSecretStore(dir);

        assertThat(store.resolve("audit-checkpoint-signing-key").bytes()).isEqualTo(privateKey);
        assertThat(store.find("audit-checkpoint-signing-key")).isPresent();
        assertThat(store.find("source-ifsc-bank-credential").orElseThrow().bytes())
                .isEqualTo("kid:s3cret".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void absentKeyIsEmptyForFindAndThrowsForResolve_neverGenerated() {
        FileSecretStore store = new FileSecretStore(dir);

        assertThat(store.find("audit-checkpoint-signing-key")).isEmpty();
        assertThatThrownBy(() -> store.resolve("audit-checkpoint-signing-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit-checkpoint-signing-key")
                .hasMessageContaining("not provisioned");
        assertThat(dir).isEmptyDirectory();
        // The dev stub's generate-on-resolve special cases must not exist here either.
        assertThatThrownBy(() -> store.resolve("consent-grant-signing-key")).isInstanceOf(IllegalStateException.class);
        assertThat(store.find("consent-grant-verifying-key")).isEmpty();
        assertThat(store.mayGenerate()).isFalse();
    }

    @Test
    void emptyFileCountsAsNotProvisioned() throws Exception {
        Files.writeString(dir.resolve("k"), " \n");
        FileSecretStore store = new FileSecretStore(dir);

        assertThat(store.find("k")).isEmpty();
        assertThatThrownBy(() -> store.resolve("k")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedBase64FailsWithoutEchoingContent() throws Exception {
        Files.writeString(dir.resolve("k"), "not*base64-TOPSECRET-value");
        FileSecretStore store = new FileSecretStore(dir);

        assertThatThrownBy(() -> store.find("k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'k'")
                .hasMessageNotContaining("TOPSECRET")
                .hasNoCause();
    }

    @Test
    void picksUpARotatedFileWithoutRestart() throws Exception {
        FileSecretStore store = new FileSecretStore(dir);
        provision("k", new byte[] {1});
        assertThat(store.resolve("k").bytes()).containsExactly(1);

        provision("k", new byte[] {2});
        assertThat(store.resolve("k").bytes()).containsExactly(2);
    }

    @Test
    void followsSymlinksLikeKubernetesSecretVolumes() throws Exception {
        Path data = Files.createDirectory(dir.resolve("..2026_data"));
        Files.writeString(data.resolve("k"), Base64.getEncoder().encodeToString(new byte[] {7}));
        Path link = dir.resolve("k");
        try {
            Files.createSymbolicLink(link, data.resolve("k"));
        } catch (UnsupportedOperationException e) {
            return; // filesystem without symlinks
        }
        assertThat(new FileSecretStore(dir).resolve("k").bytes()).containsExactly(7);
    }

    @Test
    void refusesKeysThatAreNotPlainFileNames() throws Exception {
        Path outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, Base64.getEncoder().encodeToString(new byte[] {9}));
        FileSecretStore store = new FileSecretStore(dir);

        for (String bad : new String[] {"../" + outside.getFileName(), "a/b", "..data", ".hidden", "a..b", "", " "}) {
            assertThatThrownBy(() -> store.find(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.find(null)).isInstanceOf(IllegalArgumentException.class);
        Files.deleteIfExists(outside);
    }

    @Test
    void failsFastWhenTheDirectoryIsUnsetOrMissing() {
        assertThatThrownBy(() -> new FileSecretStore((String) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("samanvay.secrets.dir");
        assertThatThrownBy(() -> new FileSecretStore(dir.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }
}
