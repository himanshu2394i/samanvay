package com.samanvay.audit.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.security.ApiAccessRefused;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes one chained audit entry per refused API call (401/403). Synchronous,
 * in its own transaction via {@link AuditService#record}; the publisher in
 * shared.security keeps the HTTP response intact if this throws.
 */
@Component
class ApiAccessRefusedAuditor {

    static final String UNAUTHENTICATED = "API_UNAUTHENTICATED";
    static final String FORBIDDEN = "API_FORBIDDEN";

    private final AuditService audit;

    ApiAccessRefusedAuditor(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    void on(ApiAccessRefused event) {
        audit.record(new AuditEntry(
                actorType(event.actorKind()),
                clip(event.actorId(), 100),
                event.status() == 401 ? UNAUTHENTICATED : FORBIDDEN,
                null,
                clip(event.method() + " " + event.path(), 200),
                null,
                null,
                null,
                Outcome.DENIED,
                clip(event.reason(), 200),
                Map.of("status", event.status())));
    }

    static ActorType actorType(String kind) {
        return switch (kind) {
            case "CITIZEN" -> ActorType.CITIZEN;
            case "OFFICER", "REVIEWER" -> ActorType.OFFICER;
            case "ADMIN" -> ActorType.ADMIN;
            case "DEPARTMENT" -> ActorType.DEPARTMENT;
            default -> ActorType.ANONYMOUS;
        };
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
