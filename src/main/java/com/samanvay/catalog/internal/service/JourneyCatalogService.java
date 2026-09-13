package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.JourneyNotFoundException;
import com.samanvay.catalog.api.JourneyPolicy;
import com.samanvay.catalog.internal.domain.JourneyEntity;
import com.samanvay.catalog.internal.repository.JourneyRepository;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class JourneyCatalogService implements JourneyCatalog {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JourneyRepository journeys;

    JourneyCatalogService(JourneyRepository journeys) {
        this.journeys = journeys;
    }

    @Override
    public JourneyDefinition byCode(String journeyCode) {
        return journeys.findById(journeyCode).map(this::toJourney).orElseThrow(() -> new JourneyNotFoundException(journeyCode));
    }

    @Override
    public JourneyPolicy policy(String journeyCode) {
        return byCode(journeyCode).policy();
    }

    @Override
    public List<JourneyDefinition> all() {
        return journeys.findAll().stream().map(this::toJourney).toList();
    }

    private JourneyDefinition toJourney(JourneyEntity e) {
        JsonNode policy = e.getPolicy() == null ? JSON.createObjectNode() : JSON.readTree(e.getPolicy());
        boolean acceptStale = policy.get("accept_stale") != null && policy.get("accept_stale").booleanValue();
        int sla = policy.get("sla_hours") == null ? 72 : policy.get("sla_hours").intValue();
        String requester = text(policy, "requester", "UNKNOWN");
        String purpose = text(policy, "purpose", "UNKNOWN");
        String prefix = text(policy, "reference_prefix", "APP");
        Map<String, String> sources = new LinkedHashMap<>();
        JsonNode src = policy.get("sources");
        if (src != null && src.isObject()) {
            src.properties().forEach(p -> sources.put(p.getKey(), p.getValue().asString()));
        }
        List<String> cats = e.getRequiredCategories() == null ? List.of() : Arrays.asList(e.getRequiredCategories());
        return new JourneyDefinition(
                e.getCode(),
                e.getName(),
                e.getBpmnRef(),
                cats,
                new JourneyPolicy(acceptStale, sla, requester, purpose, prefix, Map.copyOf(sources)),
                e.getStatus());
    }

    private static String text(JsonNode policy, String field, String fallback) {
        return policy.get(field) == null || policy.get(field).asString().isBlank()
                ? fallback
                : policy.get(field).asString();
    }
}
