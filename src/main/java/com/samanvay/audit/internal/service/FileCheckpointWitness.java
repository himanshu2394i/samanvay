package com.samanvay.audit.internal.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * File-backed {@link CheckpointWitness}: appends each signed checkpoint to an append-only witness log
 * in a configured directory (in the demo, a second container's mounted filesystem — HLD §9.4 open
 * question), and returns a content digest of the attestation as the {@code published_ref}.
 *
 * <p>Disabled unless {@code samanvay.audit.witness.dir} is set, so dev and CI without a witness mount
 * behave exactly as before (no {@code published_ref} recorded). Failures are swallowed and logged: the
 * checkpoint is still recorded, because the signed hash chain is the primary tamper-evidence.
 */
@Component
class FileCheckpointWitness implements CheckpointWitness {

    private static final Logger log = LoggerFactory.getLogger(FileCheckpointWitness.class);
    private static final String LOG_FILE = "checkpoints.log";

    private final String dir;

    FileCheckpointWitness(@Value("${samanvay.audit.witness.dir:}") String dir) {
        this.dir = dir;
    }

    @Override
    public String publish(long uptoEntrySeq, byte[] rootHash, byte[] signature, String keyId) {
        if (dir == null || dir.isBlank()) {
            return null; // witnessing not configured
        }
        String attestation = attestation(uptoEntrySeq, rootHash, signature, keyId);
        try {
            Path base = Path.of(dir);
            Files.createDirectories(base);
            Files.writeString(base.resolve(LOG_FILE), attestation + System.lineSeparator(), UTF_8, CREATE, APPEND);
            return "sha256:" + hex(sha256(attestation.getBytes(UTF_8)));
        } catch (IOException e) {
            // Best-effort: the checkpoint is still recorded from the signed chain.
            log.warn("audit checkpoint witness publication failed (best-effort): {}", e.toString());
            return null;
        }
    }

    /**
     * A deterministic, single-line attestation of the checkpoint. Two witnesses given the same
     * checkpoint produce the same line and therefore the same digest, so the {@code published_ref} is a
     * verifiable content anchor rather than a storage-specific pointer.
     */
    static String attestation(long uptoEntrySeq, byte[] rootHash, byte[] signature, String keyId) {
        Base64.Encoder b64 = Base64.getEncoder();
        return "{\"uptoEntrySeq\":" + uptoEntrySeq
                + ",\"keyId\":\"" + (keyId == null ? "v1" : keyId) + "\""
                + ",\"rootHash\":\"" + b64.encodeToString(rootHash) + "\""
                + ",\"signature\":\"" + b64.encodeToString(signature) + "\"}";
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
