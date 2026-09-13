package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RestAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;
    private final RestClient http = RestClient.create();
    private final JsonMapper json = JsonMapper.builder().build();

    RestAdapter(MockDepartmentBackend mocks) {
        this.mocks = mocks;
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
        String raw = http.get().uri(url).retrieve().body(String.class);
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
