package com.samanvay.connector.api;

import java.util.Map;

public record AdapterRequest(
        String dataSourceCode,
        String protocol,
        String host,
        String endpoint,
        String template,
        Map<String, String> boundInputs,
        String authConfigRef) {}
