package com.samanvay.catalog.api;

public interface MappingCatalog {
    MappingDefinition byRef(String mappingRef);

    MappingDefinition save(MappingDraft draft);
}
