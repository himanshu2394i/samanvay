package com.samanvay.catalog.api;

import com.samanvay.shared.DataCategory;

public record ConnectorDefinition(
        String ref,
        String connectorId,
        int version,
        String dataSourceCode,
        DataCategory category,
        String capabilitiesJson,
        String inputsJson,
        Integer slaMs,
        ConnectorStatus status) {

    public boolean supports(Capability capability) {
        return capabilitiesJson != null && capabilitiesJson.contains("\"" + capability.name() + "\"");
    }
}
