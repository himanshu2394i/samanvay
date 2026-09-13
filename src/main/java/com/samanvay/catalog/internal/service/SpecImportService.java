package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.ImportPreview;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.catalog.api.SpecImport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class SpecImportService implements SpecImport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SchemaCatalog schemas;
    private final OpenApiFlattener flattener = new OpenApiFlattener();
    private final MappingSuggestor suggestor = new MappingSuggestor();

    SpecImportService(SchemaCatalog schemas) {
        this.schemas = schemas;
    }

    @Override
    public ImportPreview preview(String openApiJson, String operationId, String targetSchemaRef) {
        List<String> sources = flattener.sourceFields(openApiJson, operationId);
        List<String> targets = schemaFields(targetSchemaRef);
        return new ImportPreview(sources, targets, suggestor.suggest(sources, targets));
    }

    private List<String> schemaFields(String schemaRef) {
        JsonNode def = JSON.readTree(schemas.definition(schemaRef));
        List<String> fields = new ArrayList<>();
        if (def.get("required") != null) {
            def.get("required").forEach(n -> fields.add(n.asString()));
        }
        if (def.get("properties") != null) {
            def.get("properties").properties().forEach(p -> {
                if (!fields.contains(p.getKey())) {
                    fields.add(p.getKey());
                }
            });
        }
        return List.copyOf(fields);
    }
}
