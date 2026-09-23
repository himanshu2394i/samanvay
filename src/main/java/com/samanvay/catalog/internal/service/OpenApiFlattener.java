package com.samanvay.catalog.internal.service;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenApiFlattener {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    List<String> sourceFields(String openApiJson, String operationId) {
        JsonNode root = JSON.readTree(openApiJson);
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return List.of();
        }
        for (var path : paths.properties()) {
            if (!path.getValue().isObject()) {
                continue;
            }
            for (var method : path.getValue().properties()) {
                JsonNode op = method.getValue();
                if (op.get("operationId") == null || !operationId.equals(op.get("operationId").asString())) {
                    continue;
                }
                return flatten(schemaOf(op));
            }
        }
        return List.of();
    }

    private static JsonNode schemaOf(JsonNode operation) {
        JsonNode responses = operation.get("responses");
        if (responses == null) {
            return null;
        }
        JsonNode ok = responses.get("200") == null ? responses.get("201") : responses.get("200");
        if (ok == null) {
            return null;
        }
        JsonNode content = ok.get("content");
        if (content == null) {
            return null;
        }
        JsonNode json = content.get("application/json");
        return json == null ? null : json.get("schema");
    }

    private static List<String> flatten(JsonNode schema) {
        if (schema == null) {
            return List.of();
        }
        JsonNode props = schema.get("properties");
        if (props == null || !props.isObject()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        props.properties().forEach(p -> out.add(p.getKey()));
        return List.copyOf(out);
    }
}
