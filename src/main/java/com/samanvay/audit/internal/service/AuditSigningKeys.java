package com.samanvay.audit.internal.service;

import com.samanvay.shared.RequiredSecrets;
import com.samanvay.shared.SecretStore;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The audit checkpoint key ring: which key id signs new checkpoints, and where each id's
 * key material lives in the {@link SecretStore}.
 *
 * <p>Naming: the {@linkplain #LEGACY_KEY_ID legacy id} {@code v1} is the key that existed
 * before rotation and keeps its original store names ({@code audit-checkpoint-signing-key},
 * {@code audit-checkpoint-verifying-key}), so an existing deployment needs no re-provisioning.
 * Any other id {@code X} uses {@code audit-checkpoint-signing-key-X} and
 * {@code audit-checkpoint-verifying-key-X}.
 *
 * <p>Rotation: provision the new pair under the new id, set
 * {@code samanvay.audit.checkpoint.signing-key-id=<id>}, restart. New checkpoints record and
 * sign with the new id; every older checkpoint still verifies against the key id stored with
 * it, so the retired <em>verifying</em> keys must stay provisioned (the retired signing key
 * can be deleted). A checkpoint with no key id predates rotation and maps to
 * {@link #LEGACY_KEY_ID}, never to the current key.
 */
@Component
public class AuditSigningKeys implements RequiredSecrets {

    /** Key id of every checkpoint written before key ids existed (stored as NULL). */
    public static final String LEGACY_KEY_ID = "v1";

    static final String SIGNING_KEY_NAME = "audit-checkpoint-signing-key";
    static final String VERIFYING_KEY_NAME = "audit-checkpoint-verifying-key";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9]{1,32}");

    private final String activeKeyId;

    @Autowired
    AuditSigningKeys(@Value("${samanvay.audit.checkpoint.signing-key-id:" + LEGACY_KEY_ID + "}") String activeKeyId) {
        if (activeKeyId == null || !KEY_ID.matcher(activeKeyId).matches()) {
            throw new IllegalStateException(
                    "samanvay.audit.checkpoint.signing-key-id must be 1-32 letters or digits (got '" + activeKeyId + "')");
        }
        this.activeKeyId = activeKeyId;
    }

    /** Signs with {@link #LEGACY_KEY_ID}: the behaviour before rotation existed. */
    static AuditSigningKeys legacyOnly() {
        return new AuditSigningKeys(LEGACY_KEY_ID);
    }

    static AuditSigningKeys withActive(String keyId) {
        return new AuditSigningKeys(keyId);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /** A missing/blank id is a pre-rotation checkpoint: signed by the legacy key. */
    static String effectiveKeyId(String storedKeyId) {
        return storedKeyId == null || storedKeyId.isBlank() ? LEGACY_KEY_ID : storedKeyId;
    }

    static String signingSecretName(String keyId) {
        return suffixed(SIGNING_KEY_NAME, keyId);
    }

    static String verifyingSecretName(String keyId) {
        return suffixed(VERIFYING_KEY_NAME, keyId);
    }

    private static String suffixed(String base, String keyId) {
        String id = effectiveKeyId(keyId);
        if (!KEY_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid audit key id");
        }
        return LEGACY_KEY_ID.equals(id) ? base : base + "-" + id;
    }

    /** What the production boot guard must find provisioned: the active key's signing and verifying halves. */
    @Override
    public List<String> keys() {
        return List.of(signingSecretName(activeKeyId), verifyingSecretName(activeKeyId));
    }
}
