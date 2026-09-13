package com.samanvay.catalog.api;

import java.util.List;

public record ConnectorTestReport(boolean passed, List<String> failures) {}
