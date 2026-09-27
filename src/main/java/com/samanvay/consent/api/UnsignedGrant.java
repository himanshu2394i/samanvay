package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.UUID;

public record UnsignedGrant(
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
        PrincipalRef principal,
        Instant issuedAt,
        Instant expiresAt) {}
