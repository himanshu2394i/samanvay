package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RestAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * {@code samanvay.connector.timeout} bounds connect and read of every real department call,
     * so a hung department surfaces as a timeout (and a released one-check claim) instead of
     * holding the claim forever.
     */
    RestAdapter(MockDepartmentBackend mocks, @Value("${samanvay.connector.timeout:PT10S}") Duration timeout) {
        this.mocks = mocks;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
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
