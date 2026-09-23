package com.samanvay.catalog.api;

import java.util.List;

public record ImportPreview(List<String> sourceFields, List<String> targetFields, List<MappingSuggestion> suggestions) {}
