package com.samanvay.catalog.api;

import java.util.Map;

public record JourneyPolicy(
        boolean acceptStale,
        int slaHours,
        String requester,
        String purpose,
        String referencePrefix,
        Map<String, String> sources) {

    public String sourceDepartment(String category) {
        String dept = sources == null ? null : sources.get(category);
        if (dept == null || dept.isBlank()) {
            throw new IllegalStateException("journey policy missing source for " + category);
        }
        return dept;
    }
}
