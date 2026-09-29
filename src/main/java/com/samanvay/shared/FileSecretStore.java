package com.samanvay.shared;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link SecretStore}: mounted secrets. One file per key in a directory
 * ({@code samanvay.secrets.dir}); the file name is the key and the content is the
 * base64 secret. That is the layout Kubernetes Secret volumes, Docker secrets and a
 * Vault Agent / CSI projection all produce, so a live Vault or KMS client can arrive
 * later without touching a caller.
 *
 * <p>Provisioned secrets only: {@link #find} is empty for an absent (or empty) file and
 * {@link #resolve} throws for it. This store never generates, defaults or falls back, so
 * nothing ephemeral can reach production through it. Files are read on every call, so a
 * rotated mount is picked up without a restart. Symlinks are followed (Kubernetes projects
 * secrets through {@code ..data}). Messages name the key, never a value.
 */
@Component
@ConditionalOnProperty(name = "samanvay.secrets.provider", havingValue = "file")
public class FileSecretStore implements SecretStore {

    /** A key is a plain file name: no separators, no leading dot (so no {@code ..data}, no traversal). */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final Path dir;

    @Autowired
    public FileSecretStore(@Value("${samanvay.secrets.dir:}") String dir) {
        this(dir == null || dir.isBlank() ? null : Path.of(dir));
    }

    public FileSecretStore(Path dir) {
        if (dir == null) {
            throw new IllegalStateException(
                    "samanvay.secrets.provider=file requires samanvay.secrets.dir (the directory of mounted secrets)");
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("samanvay.secrets.dir is not a directory: " + dir);
        }
        this.dir = dir.toAbsolutePath().normalize();
    }

    @Override
    public Secret resolve(String key) {
        return find(key).orElseThrow(() -> new IllegalStateException(
                "secret '" + key + "' is not provisioned in " + dir + " (this store never generates secrets)"));
    }

    @Override
    public Optional<Secret> find(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String encoded;
        try {
            encoded = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            // Deliberately not chained: an IO error text must not carry file content.
            throw new UncheckedIOException("secret '" + key + "' is unreadable in " + dir, new IOException("read failed"));
        }
        if (encoded.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Secret(Base64.getDecoder().decode(encoded)));
        } catch (IllegalArgumentException e) {
            // Not chained either: the decoder message can echo the offending characters.
            throw new IllegalStateException("secret '" + key + "' in " + dir + " is not valid base64");
        }
    }

    private Path fileFor(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key is required");
        }
        if (!KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("secret key '" + key + "' is not a plain file name");
        }
        return dir.resolve(key);
    }
}
