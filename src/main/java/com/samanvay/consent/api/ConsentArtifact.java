package com.samanvay.consent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        String citizenAuthRef) {}
