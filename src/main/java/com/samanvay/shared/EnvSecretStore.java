package com.samanvay.shared;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Honest Phase 0 stub: env/base64 if set, otherwise an ephemeral Ed25519 key
 * generated in-process. The dev/demo default ({@code samanvay.secrets.provider}
 * unset or {@code env}); production uses {@link FileSecretStore}, and the boot
 * guard refuses this store outside the dev/demo profiles.
 */
@Component
@ConditionalOnProperty(name = "samanvay.secrets.provider", havingValue = "env", matchIfMissing = true)
public class EnvSecretStore implements SecretStore {

    private static final String AUDIT_SIGNING = "audit-checkpoint-signing-key";
    private static final String AUDIT_VERIFYING = "audit-checkpoint-verifying-key";

    private final ConcurrentHashMap<String, Secret> cache = new ConcurrentHashMap<>();

    /** Generates ephemeral keys when nothing is provisioned, so the production guard refuses it. */
    @Override
    public boolean mayGenerate() {
        return true;
    }

    @Override
    public Secret resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key is required");
        }
        String partner = auditPartner(key);
        if (partner != null) {
            return resolveAuditKey(key, partner);
        }
        if ("consent-grant-signing-key".equals(key) || "consent-grant-verifying-key".equals(key)) {
            synchronized (cache) {
                ensureGrantKeyPair();
                return cache.get(key);
            }
        }
        return cache.computeIfAbsent(key, this::loadOrGenerate);
    }

    /** Provisioned secrets only: {@code SAMANVAY_SECRET_<KEY>} (base64), with no generated fallback. */
    @Override
    public java.util.Optional<Secret> find(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key is required");
        }
        String encoded = System.getenv(envName(key));
        if (encoded == null || encoded.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Secret(Base64.getDecoder().decode(encoded.trim())));
    }

    /**
     * The audit checkpoint signing key (private) and its verifying key (public) for one key id
     * are one pair: generating them independently would sign checkpoints nothing can verify.
     * Returns the other half's name for {@code audit-checkpoint-signing-key[-id]} /
     * {@code audit-checkpoint-verifying-key[-id]}, else null.
     */
    private static String auditPartner(String key) {
        if (key.equals(AUDIT_SIGNING) || key.startsWith(AUDIT_SIGNING + "-")) {
            return AUDIT_VERIFYING + key.substring(AUDIT_SIGNING.length());
        }
        if (key.equals(AUDIT_VERIFYING) || key.startsWith(AUDIT_VERIFYING + "-")) {
            return AUDIT_SIGNING + key.substring(AUDIT_VERIFYING.length());
        }
        return null;
    }

    private Secret resolveAuditKey(String key, String partner) {
        synchronized (cache) {
            Secret cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            String encoded = System.getenv(envName(key));
            if (encoded != null && !encoded.isBlank()) {
                return cache.computeIfAbsent(key, k -> new Secret(Base64.getDecoder().decode(encoded.trim())));
            }
            String partnerEncoded = System.getenv(envName(partner));
            if (partnerEncoded != null && !partnerEncoded.isBlank()) {
                // Generating this half would not match the provisioned one.
                throw new IllegalStateException(
                        "secret '" + key + "' is not provisioned but its pair '" + partner + "' is; provision both");
            }
            try {
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                boolean keyIsSigning = key.startsWith(AUDIT_SIGNING);
                cache.put(key, new Secret(keyIsSigning ? pair.getPrivate().getEncoded() : pair.getPublic().getEncoded()));
                cache.put(partner, new Secret(keyIsSigning ? pair.getPublic().getEncoded() : pair.getPrivate().getEncoded()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Ed25519 unavailable", e);
            }
            return cache.get(key);
        }
    }

    static String envName(String key) {
        return "SAMANVAY_SECRET_" + key.toUpperCase().replace('-', '_');
    }

    private Secret loadOrGenerate(String key) {
        String encoded = System.getenv(envName(key));
        if (encoded != null && !encoded.isBlank()) {
            return new Secret(Base64.getDecoder().decode(encoded));
        }
        if ("consent-grant-signing-key".equals(key) || "consent-grant-verifying-key".equals(key)) {
            ensureGrantKeyPair();
            return cache.get(key);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            return new Secret(pair.getPrivate().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** Signing and verifying keys must be one pair — generating them independently would mint unverifiable grants. */
    private void ensureGrantKeyPair() {
        if (cache.containsKey("consent-grant-signing-key") && cache.containsKey("consent-grant-verifying-key")) {
            return;
        }
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            cache.put("consent-grant-signing-key", new Secret(pair.getPrivate().getEncoded()));
            cache.put("consent-grant-verifying-key", new Secret(pair.getPublic().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }
}
