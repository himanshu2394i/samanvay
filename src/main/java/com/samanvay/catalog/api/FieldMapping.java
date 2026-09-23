package com.samanvay.catalog.api;

import java.util.List;

public record FieldMapping(String source, String target, List<TransformCall> transforms) {}
