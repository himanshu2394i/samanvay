package com.samanvay.connector.internal.source;

import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

    /**
     * Which source's secret to use: {@code secret:<code>} names another source's credential; anything else
     * (blank, {@code secret:none}) uses the source's own.
     */
    public static String credentialCode(String dataSourceCode, String authConfigRef) {
        if (authConfigRef != null && authConfigRef.startsWith("secret:")) {
            String named = authConfigRef.substring("secret:".length()).trim();
            if (!named.isEmpty() && !"none".equals(named)) {
                return named;
            }
        }
        return dataSourceCode;
    }

    /**
     * One required credential parameter, or an {@link com.samanvay.connector.api.IllegalConnectorConfigurationException}
     * naming the source, the parameter and the SecretStore key to provision (never any value).
     */
    public static String requireParam(String source, String code, Map<String, String> params, String name) {
        String v = params.get(name);
        if (v == null || v.isEmpty()) {
            throw new com.samanvay.connector.api.IllegalConnectorConfigurationException("source '" + source
                    + "' is missing credential parameter '" + name + "' (SecretStore key " + secretKey(code) + ")");
        }
        return v;
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

    /**
     * The secret's values by parameter name, as the department's manifest {@code auth.parameters} names them
     * (an API key header name, {@code client_id}, {@code password}...). The stored value is a JSON object of strings,
     * or the older {@code keyId:keySecret}, which is exposed as {@code username} and {@code password}. Empty when the
     * secret is missing, unreadable or malformed. Values never appear in messages or {@code toString}.
     */
    public Map<String, String> params(String sourceCode) {
        return parse(raw(sourceCode));
    }

    private record Lookup(Optional<Credential> credential, String problem) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The stored secret text, or null when it is missing or unreadable (the store's error text is dropped). */
    private String raw(String sourceCode) {
        Optional<SecretStore.Secret> secret;
        try {
            secret = secrets.find(secretKey(sourceCode));
        } catch (RuntimeException e) {
            // Deliberately dropped: a store's error text could echo the stored value.
            return null;
        }
        if (secret.isEmpty() || secret.get().bytes() == null || secret.get().bytes().length == 0) {
            return "";
        }
        return new String(secret.get().bytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parse(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        if (value.trim().startsWith("{")) {
            try {
                JsonNode n = JSON.readTree(value);
                n.properties().forEach(e -> {
                    if (e.getValue().isString()) {
                        out.put(e.getKey(), e.getValue().asString());
                    }
                });
            } catch (RuntimeException e) {
                out.clear(); // malformed: no parameters, and the parser's message (which may echo the value) is dropped
            }
            return out;
        }
        int colon = value.indexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            out.put("username", value.substring(0, colon));
            out.put("password", value.substring(colon + 1));
        }
        return out;
    }

    private Lookup lookup(String sourceCode) {
        String value = raw(sourceCode);
        if (value == null) {
            return new Lookup(Optional.empty(), "unreadable");
        }
        if (value.isEmpty()) {
            return new Lookup(Optional.empty(), "missing");
        }
        Map<String, String> p = parse(value);
        String user = p.get("username");
        String pass = p.get("password");
        if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
            return new Lookup(Optional.empty(), "malformed");
        }
        return new Lookup(Optional.of(new Credential(user, pass)), null);
    }
}
