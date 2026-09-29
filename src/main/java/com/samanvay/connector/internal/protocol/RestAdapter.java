package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RestAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;
    private final DeadlineHttp http;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Real department calls go through {@link DeadlineHttp}: one total deadline over the whole
     * exchange (retries, connect, full body), cancelled when it runs out, so a hung or trickling
     * department surfaces as a timeout (and a released one-check claim).
     */
    RestAdapter(MockDepartmentBackend mocks, DeadlineHttp http) {
        this.mocks = mocks;
        this.http = http;
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
        String url = "https://" + request.host() + encodePath(request.endpoint(), request.boundInputs());
        String raw = http.get(URI.create(url));
        JsonNode body = json.readTree(raw == null ? "{}" : raw);
        return new AdapterResponse(body, raw == null ? 0 : raw.length());
    }

    static String encodePath(String endpoint, Map<String, String> inputs) {
        String path = endpoint == null ? "" : endpoint;
        Map<String, String> q = inputs == null ? Map.of() : new LinkedHashMap<>(inputs);
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
