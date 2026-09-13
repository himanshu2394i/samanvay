package com.samanvay.catalog.api;

import java.util.List;

public record MappingDraft(String ref, String connectorRef, List<FieldMapping> rules) {}
