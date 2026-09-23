package com.samanvay.connector.internal.mapping;

import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDefinition;
import com.samanvay.connector.api.UnknownTransformException;
import com.samanvay.shared.MappingTransforms;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class MappingExecutor {

    static final Map<String, BiFunction<String, List<String>, String>> REGISTRY = Map.of(
            "trim", (v, args) -> v == null ? null : v.trim(),
            "upper", (v, args) -> v == null ? null : v.toUpperCase(Locale.ROOT),
            "lower", (v, args) -> v == null ? null : v.toLowerCase(Locale.ROOT),
            "date_parse",
                    (v, args) -> LocalDate.parse(v, DateTimeFormatter.ofPattern(args.getFirst())).toString(),
            "coalesce", (v, args) -> v != null && !v.isBlank() ? v : args.getFirst(),
            "split_name", (v, args) -> v == null ? null : v.trim().replaceAll("\\s+", " "),
            "lookup", (v, args) -> v,
            "mask", (v, args) -> maskExcept(v, Integer.parseInt(args.getFirst())));

    public JsonNode apply(MappingDefinition def, JsonNode source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (FieldMapping rule : def.rules()) {
            String value = read(source, rule.source());
            for (var t : rule.transforms()) {
                var fn = REGISTRY.get(t.fn());
                if (fn == null) {
                    throw new UnknownTransformException(t.fn());
                }
                value = fn.apply(value, t.args());
            }
            setNested(result, rule.target(), value);
        }
        return result;
    }

    public static Set<String> registryNames() {
        return REGISTRY.keySet();
    }

    static boolean matchesSharedNames() {
        return REGISTRY.keySet().equals(MappingTransforms.NAMES);
    }

    static String read(JsonNode source, String path) {
        String p = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        JsonNode n = source;
        for (String part : p.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            n = n.get(part);
            if (n == null) {
                return null;
            }
        }
        return n.isValueNode() ? n.asString() : n.toString();
    }

    private static void setNested(ObjectNode root, String target, String value) {
        String[] parts = target.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = cur.get(parts[i]);
            if (child instanceof ObjectNode on) {
                cur = on;
            } else {
                ObjectNode created = JsonNodeFactory.instance.objectNode();
                cur.set(parts[i], created);
                cur = created;
            }
        }
        cur.put(parts[parts.length - 1], value);
    }

    private static String maskExcept(String v, int keep) {
        if (v == null) {
            return null;
        }
        if (v.length() <= keep) {
            return v;
        }
        return "*".repeat(v.length() - keep) + v.substring(v.length() - keep);
    }
}
