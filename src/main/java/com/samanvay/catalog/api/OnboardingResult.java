package com.samanvay.catalog.api;

import java.util.List;

/** What onboarding created: all connectors and journeys are DRAFTs until tested and published. */
public record OnboardingResult(
        String departmentCode,
        List<String> dataSources,
        List<String> connectorRefs,
        List<String> mappingRefs,
        List<String> journeysCreated,
        List<String> skipped,
        List<PendingStep> pendingSteps) {}
