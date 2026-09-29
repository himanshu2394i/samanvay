package com.samanvay.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A disbursement issued for an approved application, with its instalments in sequence order. */
public record Disbursement(
        UUID id,
        UUID applicationId,
        UUID citizenId,
        String journeyCode,
        String status,
        Instant createdAt,
        List<Instalment> instalments) {}
