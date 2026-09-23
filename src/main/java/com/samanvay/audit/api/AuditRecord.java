package com.samanvay.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditRecord(
        long seq,
        Instant ts,
        String actorId,
        String action,
        String subjectId,
        String departmentId,
        Outcome outcome,
        String reason,
        UUID consentId) {}
