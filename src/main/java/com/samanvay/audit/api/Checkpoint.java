package com.samanvay.audit.api;

import java.time.Instant;

public record Checkpoint(
        long seq,
        long uptoEntrySeq,
        byte[] rootHash,
        Instant signedAt,
        byte[] signature,
        String publishedRef
) {}
