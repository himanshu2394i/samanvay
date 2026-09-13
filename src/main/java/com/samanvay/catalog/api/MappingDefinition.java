package com.samanvay.catalog.api;

import java.util.List;

public record MappingDefinition(String ref, String connectorRef, List<FieldMapping> rules) {}
