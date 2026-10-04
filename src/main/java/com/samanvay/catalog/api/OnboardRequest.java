package com.samanvay.catalog.api;

import java.util.List;
import java.util.Map;

/**
 * An admin's decision to onboard a reviewed department. {@code manifestDigest} must equal the digest of the plan they
 * reviewed. {@code categories} are the documents they ticked. Mappings are propose-only: an admin either approves the
 * suggestions ({@code acceptSuggestedMappings}) or supplies their own per category in {@code mappings}.
 * {@code approvedManifestKey} is the thumbprint of the manifest signing key the admin confirmed with the department; it is
 * needed whenever the manifest is signed by a key that is not yet pinned (first onboarding, or a changed key).
 * {@code acknowledgeIdentityChange} must be true when the plan shows an {@code identityChange}: the manifest would replace the
 * identity (login, keys, issuer, person-ID type, signing key) of a department that already has one.
 */
public record OnboardRequest(
        String baseUrl,
        String manifestDigest,
        List<String> categories,
        boolean acceptSuggestedMappings,
        Map<String, List<FieldMapping>> mappings,
        String approvedManifestKey,
        Boolean acknowledgeIdentityChange) {

    public OnboardRequest {
        acknowledgeIdentityChange = acknowledgeIdentityChange != null && acknowledgeIdentityChange; // absent in the JSON means no
    }

    public OnboardRequest(String baseUrl, String manifestDigest, List<String> categories, boolean acceptSuggestedMappings,
            Map<String, List<FieldMapping>> mappings, String approvedManifestKey) {
        this(baseUrl, manifestDigest, categories, acceptSuggestedMappings, mappings, approvedManifestKey, false);
    }

    public OnboardRequest(String baseUrl, String manifestDigest, List<String> categories, boolean acceptSuggestedMappings,
            Map<String, List<FieldMapping>> mappings) {
        this(baseUrl, manifestDigest, categories, acceptSuggestedMappings, mappings, null, false);
    }
}
