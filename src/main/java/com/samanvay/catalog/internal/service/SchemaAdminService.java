package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.SchemaAdmin;
import com.samanvay.catalog.api.SchemaDraft;
import com.samanvay.catalog.api.SchemaField;
import com.samanvay.catalog.api.SchemaSummary;
import com.samanvay.catalog.internal.domain.SchemaEntity;
import com.samanvay.catalog.internal.repository.SchemaRepository;
import com.samanvay.shared.InvalidRequestException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class SchemaAdminService implements SchemaAdmin {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REF = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(/[A-Za-z][A-Za-z0-9]*)*@[1-9][0-9]{0,3}$");
    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");
    private static final Pattern FIELD = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");

    private final SchemaRepository schemas;

    SchemaAdminService(SchemaRepository schemas) {
        this.schemas = schemas;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SchemaSummary> summaries() {
        return schemas.findAll().stream().map(SchemaAdminService::summary).sorted(Comparator.comparing(SchemaSummary::ref)).toList();
    }

    @Override
    @Transactional
    public SchemaSummary create(SchemaDraft draft) {
        if (draft == null) {
            throw new InvalidRequestException("a schema draft is required");
        }
        String ref = InvalidRequestException.requireText(draft.ref(), "ref").trim();
        if (!REF.matcher(ref).matches()) {
            throw new InvalidRequestException("ref must look like Name@1 or Credential/Name@2 (letters and digits, then @version)");
        }
        String category = InvalidRequestException.requireText(draft.category(), "category").trim();
        if (!CATEGORY.matcher(category).matches()) {
            throw new InvalidRequestException("category must be UPPER_SNAKE_CASE, for example INCOME_CERTIFICATE");
        }
        List<SchemaField> fields = draft.fields() == null ? List.of() : draft.fields();
        if (fields.isEmpty()) {
            throw new InvalidRequestException("a schema needs at least one field");
        }
        Set<String> seen = new HashSet<>();
        ObjectNode properties = JSON.createObjectNode();
        ArrayNode required = JSON.createArrayNode();
        for (SchemaField f : fields) {
            if (f == null || f.name() == null || !FIELD.matcher(f.name()).matches()) {
                throw new InvalidRequestException("a field name must start with a lower-case letter and use letters and digits only");
            }
            if (f.type() == null || !TYPES.contains(f.type())) {
                throw new InvalidRequestException("field " + f.name() + ": type must be one of " + String.join(", ", new TreeSet<>(TYPES)));
            }
            if (!seen.add(f.name())) {
                throw new InvalidRequestException("field " + f.name() + " is listed twice");
            }
            properties.putObject(f.name()).put("type", f.type());
            if (f.required()) {
                required.add(f.name());
            }
        }
        if (schemas.existsById(ref)) {
            throw new InvalidRequestException(
                    "Schema " + ref + " already exists. Schemas are not edited in place: add a new version (@" + (versionOf(ref) + 1) + ").");
        }
        ObjectNode definition = JSON.createObjectNode();
        definition.put("type", "object");
        definition.set("required", required);
        definition.put("x-category", category);
        definition.set("properties", properties);
        String base = ref.substring(ref.lastIndexOf('/') + 1, ref.lastIndexOf('@'));
        SchemaEntity saved = schemas.save(SchemaEntity.of(ref, base, versionOf(ref), JSON.writeValueAsString(definition)));
        return summary(saved);
    }

    private static SchemaSummary summary(SchemaEntity e) {
        JsonNode def = JSON.readTree(e.getDefinition() == null ? "{}" : e.getDefinition());
        Set<String> required = new HashSet<>();
        if (def.get("required") != null) {
            def.get("required").forEach(r -> required.add(r.asString()));
        }
        List<SchemaField> fields = new ArrayList<>();
        if (def.get("properties") != null) {
            def.get("properties").properties().forEach(p -> {
                JsonNode t = p.getValue().get("type");
                fields.add(new SchemaField(p.getKey(), t == null ? "unspecified" : t.asString(), required.contains(p.getKey())));
            });
        } else {
            required.stream().sorted().forEach(n -> fields.add(new SchemaField(n, "unspecified", true)));
        }
        JsonNode cat = def.get("x-category");
        return new SchemaSummary(e.getRef(), e.getName(), e.getVersion(), cat == null ? null : cat.asString(), fields);
    }

    private static int versionOf(String ref) {
        return Integer.parseInt(ref.substring(ref.lastIndexOf('@') + 1));
    }
}
