package com.samanvay.audit.internal.service;

import com.samanvay.audit.api.CheckpointSigningException;
import com.samanvay.audit.internal.repository.AuditEntryRepository;
import com.samanvay.audit.internal.repository.CheckpointRepository;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ChainCheckpointScheduler {

    static final String SIGNING_KEY_NAME = "audit-checkpoint-signing-key";

    private final AuditEntryRepository entries;
    private final CheckpointRepository checkpoints;
    private final SecretStore secretStore;

    ChainCheckpointScheduler(
            AuditEntryRepository entries, CheckpointRepository checkpoints, SecretStore secretStore) {
        this.entries = entries;
        this.checkpoints = checkpoints;
        this.secretStore = secretStore;
    }

    @Scheduled(cron = "0 0 * * * *")
    void createCheckpoint() {
        long uptoSeq = entries.currentMaxSeq();
        byte[] rootHash = entries.findHashAt(uptoSeq).orElseThrow();
        byte[] signature = sign(rootHash, uptoSeq, secretStore.resolve(SIGNING_KEY_NAME));
        checkpoints.insert(uptoSeq, rootHash, signature);
    }

    static byte[] sign(byte[] rootHash, long uptoSeq, SecretStore.Secret secret) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(secret.bytes()));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(payload(rootHash, uptoSeq));
            return sig.sign();
        } catch (InvalidKeySpecException e) {
            throw new CheckpointSigningException("signing key unavailable or unreadable", e);
        } catch (GeneralSecurityException e) {
            throw new CheckpointSigningException("checkpoint signing failed", e);
        }
    }

    static boolean verifySignature(byte[] rootHash, long uptoSeq, byte[] signature, byte[] publicKeyBytes) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(payload(rootHash, uptoSeq));
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] payload(byte[] rootHash, long uptoSeq) {
        byte[] seq = Long.toString(uptoSeq).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[rootHash.length + seq.length];
        System.arraycopy(rootHash, 0, out, 0, rootHash.length);
        System.arraycopy(seq, 0, out, rootHash.length, seq.length);
        return out;
    }
}
