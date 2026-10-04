package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RestAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;
    private final DeadlineHttp http;
    private final String scheme;
    private final DepartmentServiceOverrides overrides;
    private final RestAuth auth;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Real department calls go through {@link DeadlineHttp}: one total deadline over the whole
     * exchange (retries, connect, full body), cancelled when it runs out, so a hung or trickling
     * department surfaces as a timeout (and a released one-check claim).
     */
    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http) {
        this(mocks, http, "https", DepartmentServiceOverrides.NONE);
    }

    /**
     * Production wiring. {@code overrides} is empty unless the dev/demo profile points a source at the
     * standalone department service; the call is still the real HTTP exchange either way.
     */
    @Autowired
    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http, DepartmentServiceOverrides overrides, RestAuth auth) {
        this(mocks, http, "https", overrides, auth);
    }

    /** Without a credential store: only sources with no declared auth scheme (NONE) can be called. */
    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http, DepartmentServiceOverrides overrides) {
        this(mocks, http, "https", overrides, new RestAuth(new com.samanvay.connector.internal.source.SourceCredentials(k -> null), http));
    }

    /** Test seam (as in {@link SoapAdapter}): lets a test point the adapter at a plain-HTTP in-JVM server. */
    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme) {
        this(mocks, http, scheme, DepartmentServiceOverrides.NONE);
    }

    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme, DepartmentServiceOverrides overrides) {
        this(mocks, http, scheme, overrides, new RestAuth(new com.samanvay.connector.internal.source.SourceCredentials(k -> null), http, scheme));
    }

    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme, DepartmentServiceOverrides overrides, RestAuth auth) {
        this.mocks = mocks;
        this.http = http;
        this.scheme = scheme;
        this.overrides = overrides;
        this.auth = auth;
    }

    @Override
    public String protocol() {
        return "REST";
    }

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            JsonNode body = mocks.fetch(request.endpoint(), request.boundInputs());
            return new AdapterResponse(body, body.toString().length());
        }
        String origin = overrides.baseUrl(request.dataSourceCode()).orElse(scheme + "://" + request.host());
        boolean post = isPost(request);
        EndpointCheck.requireSafe(request.dataSourceCode(), request.endpoint());
        RestAuth.Applied applied = auth.apply(request, origin);
        Map<String, String> query = new LinkedHashMap<>(request.boundInputs() == null ? Map.of() : request.boundInputs());
        ObjectNode bodyJson = json.createObjectNode();
        if (post) {
            // The inputs the connector says travel in the body (a person ID does not belong in a URL) leave the query.
            for (String name : request.access().getOrDefault("body_inputs", "").split(",")) {
                String key = name.trim();
                if (!key.isEmpty() && query.containsKey(key)) {
                    bodyJson.put(key, query.remove(key));
                }
            }
        }
        String url = origin + encodePath(request.endpoint(), query, applied.query());
        URI uri = EndpointCheck.assertSameOrigin(URI.create(url), origin);
        Map<String, String> headers = new LinkedHashMap<>(applied.headers());
        if (applied.signer() != null) {
            String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            headers.putAll(applied.signer().sign(post ? "POST" : "GET", pathAndQuery, post ? bodyJson.toString() : ""));
        }
        String raw = post
                ? http.post(uri, bodyJson.toString(), "application/json", headers, applied.tls())
                : http.get(uri, headers, applied.tls());
        JsonNode body = json.readTree(raw == null ? "{}" : raw);
        return new AdapterResponse(body, raw == null ? 0 : raw.length());
    }

    /** Endpoint with {@code {name}} segments filled from the inputs (URL-encoded); the remaining inputs become the query. */
    /** GET (the default) or POST; anything else is refused before a call is made. */
    private static boolean isPost(AdapterRequest request) {
        String method = request.access().getOrDefault("method", "").trim().toUpperCase(java.util.Locale.ROOT);
        if (method.isEmpty() || method.equals("GET")) {
            return false;
        }
        if (method.equals("POST")) {
            return true;
        }
        throw new com.samanvay.connector.api.IllegalConnectorConfigurationException(
                "REST source '" + request.dataSourceCode() + "' declares an unsupported method; only GET and POST are supported");
    }

    static String encodePath(String endpoint, Map<String, String> inputs) {
        return encodePath(endpoint, inputs, Map.of());
    }

    /** As above, plus {@code extraQuery} (e.g. an API key the department wants in the query string) appended after. */
    static String encodePath(String endpoint, Map<String, String> inputs, Map<String, String> extraQuery) {
        String path = endpoint == null ? "" : endpoint;
        Map<String, String> q = inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputs);
        for (var e : new LinkedHashMap<>(q).entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            if (path.contains(placeholder)) {
                path = path.replace(placeholder, URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
                q.remove(e.getKey());
            }
        }
        q.putAll(extraQuery);
        if (q.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        sb.append(path.contains("?") ? "&" : "?");
        boolean first = true;
        for (var e : q.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
