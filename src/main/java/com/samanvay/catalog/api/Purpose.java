package com.samanvay.catalog.api;

import java.util.List;

/**
 * A governed purpose code (DEPA consent purpose shape: code, refUri, text, category type),
 * plus what a consent record needs about it. All of it is catalog data: new purposes need no
 * Java change.
 *
 * @param requesterDepartment the department that may request consent under this purpose
 * @param dataCategories the data categories a consent for this purpose covers (drive fetches)
 * @param dataTypes the DEPA data-type descriptors shown to the citizen and copied onto the
 *     consent record at grant time
 * @param requesterRule how the requester is fixed; see {@link RequesterRule}
 * @param maxDurationDays cap on a consent's lifetime, or {@code null} for the platform default
 * @param durationRule when the consent is meant to end within the cap (informational)
 * @param frequency how often data may be checked under it; ONCE and ONCE_PER_DOCUMENT_PER_APPLICATION are enforced as one check per document per application (V189), other values are recorded only
 * @param labelEn English label, {@code null} while {@code labelEnStatus} is MISSING
 * @param labelMr Marathi label, {@code null} while {@code labelMrStatus} is MISSING
 * @param separateOptIn must be asked for on its own, never bundled with another purpose
 */
public record Purpose(
        String code,
        String text,
        String refUri,
        String categoryType,
        boolean active,
        String requesterDepartment,
        List<String> dataCategories,
        List<String> dataTypes,
        RequesterRule requesterRule,
        Integer maxDurationDays,
        String durationRule,
        String frequency,
        String labelEn,
        String labelMr,
        LabelStatus labelEnStatus,
        LabelStatus labelMrStatus,
        boolean separateOptIn) {

    /** How the requester of a consent under this purpose is fixed. Never from a request body. */
    public enum RequesterRule {
        /** The purpose's {@code requesterDepartment}. */
        CATALOG_DEPARTMENT,
        /**
         * The department that approved the citizen's award in the prior year (it must also be
         * the purpose's {@code requesterDepartment}); with no such award the request is refused.
         */
        PRIOR_AWARD_DEPARTMENT
    }

    /** Review state of a translated label. */
    public enum LabelStatus {
        MISSING,
        DRAFT,
        APPROVED
    }

    public Purpose {
        dataCategories = dataCategories == null ? List.of() : List.copyOf(dataCategories);
        dataTypes = dataTypes == null ? List.of() : List.copyOf(dataTypes);
        requesterRule = requesterRule == null ? RequesterRule.CATALOG_DEPARTMENT : requesterRule;
        labelEnStatus = labelEnStatus == null ? LabelStatus.MISSING : labelEnStatus;
        labelMrStatus = labelMrStatus == null ? LabelStatus.MISSING : labelMrStatus;
    }

    /** A purpose with only the V181/V184 fields (catalog defaults for the rest). */
    public Purpose(
            String code,
            String text,
            String refUri,
            String categoryType,
            boolean active,
            String requesterDepartment,
            List<String> dataCategories) {
        this(code, text, refUri, categoryType, active, requesterDepartment, dataCategories,
                List.of(), RequesterRule.CATALOG_DEPARTMENT, null, null, null, null, null,
                LabelStatus.MISSING, LabelStatus.MISSING, false);
    }
}
