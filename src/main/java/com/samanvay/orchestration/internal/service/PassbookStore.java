package com.samanvay.orchestration.internal.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores an uploaded passbook on disk under a generated name (no client-supplied
 * path, so no traversal), and hands back the path and a SHA-256 of the content.
 * Deletion is best-effort and logged; the durable record is the hash in the DB.
 */
@Component
class PassbookStore {

    private static final Logger log = LoggerFactory.getLogger(PassbookStore.class);

    private final Path dir;

    PassbookStore(BankReviewProperties properties) {
        this.dir = Path.of(properties.uploadDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create passbook upload dir " + dir, e);
        }
    }

    record Stored(String path, String sha256) {}

    Stored write(byte[] content, PassbookUpload.Kind kind) {
        Path file = dir.resolve(UUID.randomUUID() + kind.extension());
        try {
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store passbook", e);
        }
        return new Stored(file.toString(), sha256Hex(content));
    }

    /** Deletes the file if it is still there; never fails the caller. */
    void deleteQuietly(String path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            log.warn("could not delete passbook file (will retry on the next purge): {}", e.toString());
        }
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
