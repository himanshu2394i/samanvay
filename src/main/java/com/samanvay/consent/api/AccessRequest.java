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
        String applicationId,
        String paymentId) {

    /**
     * {@code principal} is who triggered this access (officer subject, department
     * client id, or the citizen themself), taken from the authenticated token by
     * the caller. It is mandatory: there is no anonymous or "system" access path.
     * {@code applicationId} is the application (journey instance) the check is for; it keys
     * one-check-per-application consents. {@code paymentId} is the payment/instalment the check is
     * for (a {@code payments} instalment id); it keys one-check-per-payment consents
     * ({@code ONCE_PER_PAYMENT}), whose scope is a keyed HMAC of it, never the raw id.
     */
    public AccessRequest {
        java.util.Objects.requireNonNull(principal, "principal");
    }

    /** With an application but no payment: every consent frequency except {@code ONCE_PER_PAYMENT}. */
    public AccessRequest(
            SubjectRef subject,
            RequesterRef requester,
            DataCategory category,
            String departmentCode,
            String connectorRef,
            PurposeCode purpose,
            String journeyCode,
            PrincipalRef principal,
            String applicationId) {
        this(subject, requester, category, departmentCode, connectorRef, purpose, journeyCode, principal, applicationId, null);
    }

    /**
     * Without an application: fine for consents with no per-application check rule; a consent
     * that allows one check per application refuses it ({@code APPLICATION_REQUIRED}), and a
     * {@code ONCE_PER_PAYMENT} consent refuses it too ({@code PAYMENT_REQUIRED}).
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
        this(subject, requester, category, departmentCode, connectorRef, purpose, journeyCode, principal, null, null);
    }
}
