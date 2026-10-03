package com.samanvay.catalog.api;

import java.util.List;

/**
 * What onboarding a department from its manifest WOULD do, with no change made: for the admin to review and tick.
 * {@code manifestDigest} identifies exactly the manifest that was reviewed; onboarding refuses if it has changed since.
 * {@code departmentExists} is true for a department seeded long ago too; {@code onboardedFromManifest} says it was onboarded from
 * a manifest (a digest is stored), so "already done" can be told from "exists but never onboarded this way".
 */
public record OnboardingPlan(
        String departmentCode,
        String departmentName,
        String manifestDigest,
        boolean departmentExists,
        boolean onboardedFromManifest,
        boolean changedSinceOnboarding,
        List<DocumentPlan> documents,
        List<JourneyPlan> journeys,
        List<PendingStep> pendingSteps,
        String manifestKeyThumbprint,
        String pinnedKeyThumbprint) {

    /**
     * {@code manifestKeyThumbprint} is the key that signed the manifest just fetched (null = unsigned); {@code pinnedKeyThumbprint}
     * is the key an admin approved earlier (null = none yet). They differ on first onboarding and when a department changes key.
     */
    public OnboardingPlan(
            String departmentCode,
            String departmentName,
            String manifestDigest,
            boolean departmentExists,
            boolean onboardedFromManifest,
            boolean changedSinceOnboarding,
            List<DocumentPlan> documents,
            List<JourneyPlan> journeys,
            List<PendingStep> pendingSteps) {
        this(departmentCode, departmentName, manifestDigest, departmentExists, onboardedFromManifest, changedSinceOnboarding, documents,
                journeys, pendingSteps, null, null);
    }

    public OnboardingPlan withManifestKey(String manifestKeyThumbprint, String pinnedKeyThumbprint) {
        return new OnboardingPlan(departmentCode, departmentName, manifestDigest, departmentExists, onboardedFromManifest,
                changedSinceOnboarding, documents, journeys, pendingSteps, manifestKeyThumbprint, pinnedKeyThumbprint);
    }

    /**
     * One document. {@code centralSchemaRef} is null when no central schema is seeded for its category (seed it first).
     * {@code suggestions} are propose-only field mappings; {@code unmappedRequired} are required central fields no
     * suggestion covers. {@code ready} means it can be onboarded as proposed.
     */
    public record DocumentPlan(
            String category,
            String title,
            String protocol,
            String dataSourceCode,
            String connectorId,
            boolean newVersionOfExisting,
            String centralSchemaRef,
            List<MappingSuggestion> suggestions,
            List<String> unmappedRequired,
            List<String> problems,
            boolean ready) {}

    public record JourneyPlan(String code, String name, boolean exists, List<String> requiredCategories, String portalUrl) {}
}
