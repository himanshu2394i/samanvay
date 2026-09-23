package com.samanvay.audit.internal.repository;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.Outcome;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntryRow(
        long seq,
        Instant ts,
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
        Map<String, Object> meta,
        byte[] prevHash,
        byte[] hash
) {

    public AuditEntry toEntry() {
        return new AuditEntry(
                actorType,
                actorId,
                action,
                subjectId,
                resource,
                departmentId,
                consentId,
                grantId,
                outcome,
                reason,
                meta);
    }
}
