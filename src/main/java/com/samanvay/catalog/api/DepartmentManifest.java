package com.samanvay.catalog.api;

import java.util.List;

/**
 * A department's self-published capability manifest (fetched from
 * {@code {baseUrl}/.well-known/samanvay/manifest}). Non-sensitive metadata only: what documents
 * the department holds and how to fetch them, and the journeys it offers — never any citizen
 * values. The middle layer uses this to onboard a department from just its base URL.
 */
public record DepartmentManifest(
        int manifestVersion,
        Dept department,
        List<Document> documents,
        List<Journey> journeys) {

    public record Dept(String code, String name, String description) {}

    public record Document(
            String category,
            String title,
            String protocol,
            String method,
            String path,
            List<Input> inputs,
            List<Field> fields) {}

    public record Input(String name, String in, boolean required, String description) {}

    public record Field(String name, String type, boolean sensitive) {}

    public record Journey(
            String code,
            String name,
            String description,
            String referencePrefix,
            int slaHours,
            String consentPurpose,
            String requester,
            List<RequiredCategory> requiredCategories) {}

    /** A document category a journey needs, and the department that provides it. */
    public record RequiredCategory(String category, String department) {}
}
