package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.UUID;

public record AccessGrant(
        UUID id,
        byte[] nonce,
        UUID consentId,
        int consentVersion,
        SubjectRef subject,
        RequesterRef requester,
        DataCategory category,
        String departmentCode,
        String connectorRef,
        PurposeCode purpose,
        Instant issuedAt,
        Instant expiresAt,
        byte[] signature) {

    public UnsignedGrant withoutSignature() {
        return new UnsignedGrant(
                id,
                nonce,
                consentId,
                consentVersion,
                subject,
                requester,
                category,
                departmentCode,
                connectorRef,
                purpose,
                issuedAt,
                expiresAt);
    }

    public AccessGrant withCategory(DataCategory tampered) {
        return new AccessGrant(
                id,
                nonce,
                consentId,
                consentVersion,
                subject,
                requester,
                tampered,
                departmentCode,
                connectorRef,
                purpose,
                issuedAt,
                expiresAt,
                signature);
    }
}
