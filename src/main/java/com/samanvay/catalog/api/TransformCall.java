package com.samanvay.catalog.api;

import java.util.List;

public record TransformCall(String fn, List<String> args) {}
