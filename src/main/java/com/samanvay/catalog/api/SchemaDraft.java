package com.samanvay.catalog.api;

import java.util.List;

/** A new central schema (or a new version of one): {@code ref} is {@code Name@N}, e.g. {@code Credential/Marks@2}. */
public record SchemaDraft(String ref, String category, List<SchemaField> fields) {}
