package com.samanvay.catalog.api;

import java.util.List;
import java.util.Map;

/**
 * A journey (service) to register, as published by a department in its manifest. Created as a DRAFT;
 * an admin publishes it once every required category has a published connector for its provider
 * department. {@code sources} maps each required category to the department that provides it.
 */
public record JourneyDraft(
        String code,
        String name,
        String referencePrefix,
        int slaHours,
        String consentPurpose,
        String requester,
        List<String> requiredCategories,
        Map<String, String> sources,
        String portalUrl) {

    /** A journey with no portal address. */
    public JourneyDraft(
            String code,
            String name,
            String referencePrefix,
            int slaHours,
            String consentPurpose,
            String requester,
            List<String> requiredCategories,
            Map<String, String> sources) {
        this(code, name, referencePrefix, slaHours, consentPurpose, requester, requiredCategories, sources, null);
    }
}
