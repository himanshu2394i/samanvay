package com.samanvay.audit.internal.service;

/**
 * Publishes a signed audit checkpoint to an <b>external witness</b> (HLD §9.4): an append-only sink
 * outside the application database, so that even collusion with full database access cannot silently
 * rewrite history — the witnessed checkpoints remain as an independent anchor.
 *
 * <p>Best-effort by contract: an implementation returns a reference to the published attestation (to
 * store in {@code audit_checkpoint.published_ref}), or {@code null} when witnessing is disabled or the
 * publication failed. A failed or absent publication must never stop a checkpoint being recorded — the
 * signed hash chain is the primary tamper-evidence; the witness strengthens it.
 */
interface CheckpointWitness {

    /**
     * @return a reference to the published attestation (e.g. a content digest), or {@code null} if
     *     witnessing is disabled or publication failed.
     */
    String publish(long uptoEntrySeq, byte[] rootHash, byte[] signature, String keyId);

    /** A witness that publishes nothing (returns {@code null}); the default when none is configured. */
    static CheckpointWitness disabled() {
        return (uptoEntrySeq, rootHash, signature, keyId) -> null;
    }
}
