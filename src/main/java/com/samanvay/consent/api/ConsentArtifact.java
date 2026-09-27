package com.samanvay.consent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A DEPA-style consent record: one citizen, one requester, one catalog purpose.
 * {@code validFrom} is when it was granted and {@code validUntil} when it expires
 * (capped by the purpose's max duration). {@code dataTypes} were copied from the
 * catalog purpose at grant time. {@code status} is worked out at read time (an active record
 * past {@code validUntil} reads as EXPIRED); {@code statusLabel} is its citizen-facing label
 * ("Active", "Ended", "Withdrawn by you").
 */
public record ConsentArtifact(
        UUID id,
        UUID citizenId,
        String requesterId,
        String purposeCode,
        List<String> categories,
        String granularity,
        Instant validFrom,
        Instant validUntil,
        Integer frequencyLimit,
        String status,
        int version,
        String citizenAuthRef,
        List<String> dataTypes,
        Instant createdAt,
        Instant revokedAt,
        String revokedBy,
        String statusLabel) {}
