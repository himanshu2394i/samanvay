package com.samanvay.catalog.api;

/**
 * Onboarding a department in one go from its published manifest (docs/FINAL-CHANGES.md section 10): review a plan, then
 * create the department, its data sources, connectors, mappings and journeys.
 */
public interface ManifestOnboarding {

    /** Fetches the manifest at {@code baseUrl} and says what onboarding would do. Changes nothing. */
    OnboardingPlan plan(String baseUrl);

    /** Creates what the admin reviewed and ticked (all as DRAFT), in one transaction. */
    OnboardingResult onboard(OnboardRequest request);
}
