package com.samanvay.catalog.api;

public record MappingSuggestion(String source, String target, double confidence, String rationale, boolean approved) {}
