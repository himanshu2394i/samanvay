package com.samanvay.audit.internal.web;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/audit")
class AuditPingController { // Phase 0 scaffolding — remove once Phase 1 has a real audited write

    private final AuditService auditService;

    AuditPingController(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/ping")
    AuditRef ping(@RequestBody PingRequest req) {
        return auditService.record(new AuditEntry(
                ActorType.SYSTEM,
                "phase0-ping",
                "PING",
                req.subjectId(),
                null,
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of()));
    }

    record PingRequest(String subjectId) {}
}
