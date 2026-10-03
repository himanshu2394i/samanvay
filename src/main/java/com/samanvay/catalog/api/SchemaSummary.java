package com.samanvay.catalog.api;

import java.util.List;

/** A central schema as the admin screen shows it. {@code category} is the document category it describes (null for older schemas). */
public record SchemaSummary(String ref, String name, int version, String category, List<SchemaField> fields) {}
