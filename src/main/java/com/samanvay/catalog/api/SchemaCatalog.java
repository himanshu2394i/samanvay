package com.samanvay.catalog.api;

import tools.jackson.databind.JsonNode;

public interface SchemaCatalog {
    String definition(String schemaRef);

    ValidationResult validate(String schemaRef, JsonNode document);
}
