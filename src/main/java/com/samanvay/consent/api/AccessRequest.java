package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;

public record AccessRequest(
        SubjectRef subject,
        RequesterRef requester,
        DataCategory category,
        String departmentCode,
        String connectorRef,
        PurposeCode purpose,
        String journeyCode,
        PrincipalRef principal,
        String applicationId) {

    /**
     * {@code principal} is who triggered this access (officer subject, department
     * client id, or the citizen themself), taken from the authenticated token by
     * the caller. It is mandatory: there is no anonymous or "system" access path.
     * {@code applicationId} is the application (journey instance) the check is for; it keys
     * one-check-per-application consents.
     */
    public AccessRequest {
        java.util.Objects.requireNonNull(principal, "principal");
    }

    /**
     * Without an application: fine for consents with no per-application check rule; a consent
     * that allows one check per application refuses it ({@code APPLICATION_REQUIRED}).
     */
    public AccessRequest(
            SubjectRef subject,
            RequesterRef requester,
            DataCategory category,
            String departmentCode,
            String connectorRef,
            PurposeCode purpose,
            String journeyCode,
            PrincipalRef principal) {
        this(subject, requester, category, departmentCode, connectorRef, purpose, journeyCode, principal, null);
    }
}
