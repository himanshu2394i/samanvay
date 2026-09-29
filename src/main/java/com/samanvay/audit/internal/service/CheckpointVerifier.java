package com.samanvay.audit.internal.service;

import com.samanvay.audit.api.Checkpoint;
import com.samanvay.shared.SecretStore;
import org.springframework.stereotype.Component;

/**
 * Verifies a stored checkpoint against the key id it was signed with, not the current
 * signing key, so rotating the key never invalidates history. A checkpoint with no key id
 * (written before rotation) is checked against the legacy key. An unknown or unprovisioned
 * key id is a failed verification (never a silent pass, never a fallback to another key).
 */
@Component
public class CheckpointVerifier {

    /** {@code keyId} is the id actually used (the legacy id for a checkpoint that stored none). */
    public record Result(boolean valid, String keyId, String reason) {}

    private final SecretStore secretStore;

    CheckpointVerifier(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    public Result verify(Checkpoint checkpoint) {
        String keyId = AuditSigningKeys.effectiveKeyId(checkpoint.keyId());
        String secretName;
        try {
            secretName = AuditSigningKeys.verifyingSecretName(keyId);
        } catch (IllegalArgumentException e) {
            return new Result(false, keyId, "checkpoint names a malformed key id");
        }
        byte[] publicKey;
        try {
            publicKey = secretStore.resolve(secretName).bytes();
        } catch (RuntimeException e) {
            // The store's error text is dropped on purpose: it must not echo secret material.
            return new Result(false, keyId, "verifying key '" + secretName + "' for key id " + keyId + " is unavailable");
        }
        boolean ok = ChainCheckpointScheduler.verifySignature(
                checkpoint.rootHash(), checkpoint.uptoEntrySeq(), checkpoint.signature(), publicKey);
        return ok
                ? new Result(true, keyId, null)
                : new Result(false, keyId, "signature does not verify under key id " + keyId);
    }
}
