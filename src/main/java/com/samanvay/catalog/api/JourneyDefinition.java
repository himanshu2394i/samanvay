package com.samanvay.catalog.api;

import java.util.List;

/**
 * @param academicYearStartMonth the scheme's academic-year start month (1-12), or {@code null}
 *     when the scheme has no academic year
 */
public record JourneyDefinition(
        String code,
        String name,
        String bpmnRef,
        List<String> requiredCategories,
        JourneyPolicy policy,
        String status,
        Integer academicYearStartMonth) {

    public JourneyDefinition(
            String code, String name, String bpmnRef, List<String> requiredCategories, JourneyPolicy policy, String status) {
        this(code, name, bpmnRef, requiredCategories, policy, status, null);
    }
}
