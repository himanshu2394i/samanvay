package com.samanvay.catalog.api;

import java.util.List;

public record JourneyDefinition(
        String code, String name, String bpmnRef, List<String> requiredCategories, JourneyPolicy policy, String status) {}
