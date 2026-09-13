package com.samanvay.shared;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Honest Phase 0 stub: env/base64 if set, otherwise an ephemeral Ed25519 key
 * generated in-process. Swap for Vault without touching callers.
 */
@Component
public class EnvSecretStore implements SecretStore {

    private final ConcurrentHashMap<String, Secret> cache = new ConcurrentHashMap<>();

    @Override
    public Secret resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secret key is required");
        }
        if ("consent-grant-signing-key".equals(key) || "consent-grant-verifying-key".equals(key)) {
            synchronized (cache) {
                ensureGrantKeyPair();
                return cache.get(key);
            }
        }
        return cache.computeIfAbsent(key, this::loadOrGenerate);
    }

    private Secret loadOrGenerate(String key) {
        String envName = "SAMANVAY_SECRET_" + key.toUpperCase().replace('-', '_');
        String encoded = System.getenv(envName);
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
