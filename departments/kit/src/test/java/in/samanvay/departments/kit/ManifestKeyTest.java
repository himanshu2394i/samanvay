package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nimbusds.jose.jwk.ECKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestKeyTest {

    @TempDir
    Path dir;

    @Test
    void the_key_is_created_once_and_loaded_afterwards() throws Exception {
        Path file = dir.resolve("sub/key.jwk");
        ECKey first = ManifestKey.loadOrCreate(file);
        assertThat(first.isPrivate()).isTrue();
        assertThat(ManifestKey.loadOrCreate(file).computeThumbprint()).isEqualTo(first.computeThumbprint());
    }

    @Test
    void no_temporary_file_is_left_behind() throws Exception {
        ManifestKey.loadOrCreate(dir.resolve("key.jwk"));
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("key.jwk");
        }
    }

    @Test
    void on_a_posix_system_the_file_is_owner_only_from_the_moment_it_exists() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        Path key = dir.resolve("key.jwk");
        ManifestKey.loadOrCreate(key);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
        Path secret = dir.resolve("secret");
        SecretFiles.writeOwnerOnly(secret, "value");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secret))).isEqualTo("rw-------");
    }

    @Test
    void writing_a_secret_replaces_the_file_atomically_and_keeps_the_content() throws Exception {
        Path f = dir.resolve("s.txt");
        SecretFiles.writeOwnerOnly(f, "one");
        SecretFiles.writeOwnerOnly(f, "two");
        assertThat(Files.readString(f)).isEqualTo("two");
    }
}
