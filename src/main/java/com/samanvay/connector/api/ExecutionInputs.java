package com.samanvay.connector.api;

import com.samanvay.shared.DataCategory;
import java.util.Map;

public record ExecutionInputs(
        DataCategory expectedCategory,
        String workflowInstanceId,
        Map<String, String> link,
        Map<String, String> profile,
        Map<String, String> journeyVars) {}
