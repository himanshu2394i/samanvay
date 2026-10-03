package com.samanvay.connector.internal.source;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The non-secret half of how Samanvay authenticates to a department source: the scheme and which parameters it
 * needs and where each goes. It comes from the department's manifest {@code auth} block (saved on the data source
 * at onboarding). Values are never here; they are looked up by parameter name in {@link SourceCredentials}.
 *
 * <p>Schemes: NONE, API_KEY, BASIC, OAUTH2_CLIENT, WS_SECURITY_USERNAME, PASSWORD, DB_USER. With no spec, the data
 * source's stored {@code auth_type} decides, so sources onboarded before manifests keep working unchanged.
 */
public record AuthSpec(String scheme, String tokenUrl, List<String> scopes, String passwordType, List<Param> parameters) {

    /** {@code in}: where the value goes (header, query, token-request, soap-header, sftp, db...). */
    public record Param(String name, String in, boolean secret) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static AuthSpec of(String authType, String specJson) {
        String fallback = authType == null || authType.isBlank() ? "NONE" : authType.trim();
        if (specJson == null || specJson.isBlank()) {
            return new AuthSpec(fallback, null, List.of(), null, List.of());
        }
        JsonNode n;
        try {
            n = JSON.readTree(specJson);
        } catch (RuntimeException e) {
            // A broken spec must not take the source down; fall back to the stored auth type with no parameters.
            return new AuthSpec(fallback, null, List.of(), null, List.of());
        }
        String scheme = text(n, "scheme");
        List<String> scopes = new ArrayList<>();
        JsonNode sc = n.get("scopes");
        if (sc != null && sc.isArray()) {
            sc.forEach(s -> scopes.add(s.asString()));
        }
        List<Param> params = new ArrayList<>();
        JsonNode ps = n.get("parameters");
        if (ps != null && ps.isArray()) {
            ps.forEach(p -> params.add(new Param(text(p, "name"), text(p, "in"), p.get("secret") != null && p.get("secret").asBoolean(false))));
        }
        return new AuthSpec(scheme == null ? fallback : scheme, text(n, "tokenUrl"), List.copyOf(scopes), text(n, "passwordType"), List.copyOf(params));
    }

    public boolean isNone() {
        return "NONE".equals(scheme);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }
}
