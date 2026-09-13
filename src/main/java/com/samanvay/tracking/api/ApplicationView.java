package com.samanvay.tracking.api;

import java.time.Instant;
import java.util.UUID;

public record ApplicationView(
        String referenceNo, UUID citizenId, String journeyCode, String status, Instant submittedAt, Instant slaDueAt) {}
