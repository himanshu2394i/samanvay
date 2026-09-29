package com.samanvay.audit.api;

import java.time.Instant;

/**
 * @param keyId the id of the signing key (see the audit LLD, key rotation); {@code null} for a
 *     checkpoint written before key ids existed, which was signed by the legacy key {@code v1}
 */
public record Checkpoint(
        long seq,
        long uptoEntrySeq,
        byte[] rootHash,
        Instant signedAt,
        byte[] signature,
        String publishedRef,
        String keyId
) {

    /** A checkpoint with no recorded key id (pre-rotation). */
    public Checkpoint(
            long seq, long uptoEntrySeq, byte[] rootHash, Instant signedAt, byte[] signature, String publishedRef) {
        this(seq, uptoEntrySeq, rootHash, signedAt, signature, publishedRef, null);
    }
}
