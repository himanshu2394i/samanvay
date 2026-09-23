package com.samanvay.catalog.api;

public interface SpecImport {
    ImportPreview preview(String openApiJson, String operationId, String targetSchemaRef);
}
