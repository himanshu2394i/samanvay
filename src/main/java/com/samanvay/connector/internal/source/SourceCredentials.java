package com.samanvay.connector.internal.source;

import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * External-source credentials, read through {@link SecretStore#find} under one
 * key per source: {@code source-<code>-credential}, value {@code keyId:keySecret}.
 * The same call serves every mode (simulator, live), so the simulator exercises
 * the same credential path as live.
 *
 * <p>Messages name the source and the SecretStore key, never a value.
 */
@Component
public class SourceCredentials {

    /** Redacted in {@link #toString()}. */
    public record Credential(String keyId, String keySecret) {
        @Override
        public String toString() {
            return "Credential[<redacted>]";
        }
    }

    private final SecretStore secrets;

    public SourceCredentials(SecretStore secrets) {
        this.secrets = secrets;
    }

    public static String secretKey(String sourceCode) {
        return "source-" + sourceCode + "-credential";
    }

    /** Empty when the credential is missing or malformed. Read on every call, so rotation needs no restart. */
    public Optional<Credential> find(String sourceCode) {
        return lookup(sourceCode).credential();
    }

    /** For boot checks: the credential, or an IllegalStateException naming the source (no value). */
    public Credential require(String sourceCode, SourceMode mode) {
        Lookup lookup = lookup(sourceCode);
        if (lookup.credential().isPresent()) {
            return lookup.credential().get();
        }
        throw new IllegalStateException("Refusing to start: source '" + sourceCode + "' is configured " + mode
                + " but its credential is " + lookup.problem() + " in SecretStore (key " + secretKey(sourceCode)
                + ", expected keyId:keySecret)");
    }

    private record Lookup(Optional<Credential> credential, String problem) {}

    private Lookup lookup(String sourceCode) {
        Optional<SecretStore.Secret> secret;
        try {
            secret = secrets.find(secretKey(sourceCode));
        } catch (RuntimeException e) {
            // Deliberately dropped: a store's error text could echo the stored value.
            return new Lookup(Optional.empty(), "unreadable");
        }
        if (secret.isEmpty() || secret.get().bytes() == null || secret.get().bytes().length == 0) {
            return new Lookup(Optional.empty(), "missing");
        }
        String value = new String(secret.get().bytes(), StandardCharsets.UTF_8);
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return new Lookup(Optional.empty(), "malformed");
        }
        return new Lookup(Optional.of(new Credential(value.substring(0, colon), value.substring(colon + 1))), null);
    }
}
