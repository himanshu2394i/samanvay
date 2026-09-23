package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.catalog.api.TransformCall;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CatalogMappingParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CatalogMappingParser() {}

    static MappingDefinition parse(String ref, String connectorRef, String rulesJson) {
        JsonNode arr = MAPPER.readTree(rulesJson);
        List<FieldMapping> rules = new ArrayList<>();
        for (JsonNode rule : arr) {
            List<TransformCall> transforms = new ArrayList<>();
            if (rule.get("transforms") != null) {
                for (JsonNode t : rule.get("transforms")) {
                    List<String> args = new ArrayList<>();
                    if (t.get("args") != null) {
                        t.get("args").forEach(a -> args.add(a.asString()));
                    }
                    transforms.add(new TransformCall(t.get("fn").asString(), args));
                }
            }
            rules.add(new FieldMapping(rule.get("source").asString(), rule.get("target").asString(), transforms));
        }
        return new MappingDefinition(ref, connectorRef, rules);
    }

    static String toJson(List<FieldMapping> rules) {
        return MAPPER.writeValueAsString(rules);
    }
}
