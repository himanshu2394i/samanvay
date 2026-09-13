package com.samanvay.audit.api;

import java.util.Map;
import java.util.UUID;

public record AuditEntry(
        ActorType actorType,
        String actorId,
        String action,
        String subjectId,
        String resource,
        String departmentId,
        UUID consentId,
        UUID grantId,
        Outcome outcome,
        String reason,
        Map<String, Object> meta
) {}
