package com.samanvay.catalog.api;

import com.samanvay.shared.DataCategory;

public record ConnectorDraft(
        String connectorId,
        String dataSourceCode,
        DataCategory category,
        String capabilitiesJson,
        String inputsJson,
        Integer slaMs) {}
