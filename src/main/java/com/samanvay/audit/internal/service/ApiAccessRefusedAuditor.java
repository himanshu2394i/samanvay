package com.samanvay.audit.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.security.ApiAccessRefused;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one chained audit entry per refused (403) API call. Synchronous,
 * in its own (REQUIRES_NEW) transaction through {@link AuditService#record},
 * so the chain lock covers read-previous-hash + insert even though the
 * request was refused in the security filter chain, outside any business
 * transaction. The publisher in shared.security keeps the HTTP response
 * intact if this throws.
 */
@Component
class ApiAccessRefusedAuditor {

    static final String UNAUTHENTICATED = "API_UNAUTHENTICATED";
    static final String FORBIDDEN = "API_FORBIDDEN";

    private final AuditService audit;
    private final TransactionTemplate ownTransaction;

    ApiAccessRefusedAuditor(AuditService audit, PlatformTransactionManager transactions) {
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    void on(ApiAccessRefused event) {
        ownTransaction.executeWithoutResult(tx -> audit.record(entryFor(event)));
    }

    static AuditEntry entryFor(ApiAccessRefused event) {
        return new AuditEntry(
                actorType(event.actorKind()),
                clip(event.actorId(), 100),
                event.status() == 401 ? UNAUTHENTICATED : FORBIDDEN, // 401s are no longer published
                null,
                clip(event.route(), 200),
                null,
                null,
                null,
                Outcome.DENIED,
                clip(event.reason(), 200),
                Map.of("status", event.status()));
    }

    static ActorType actorType(String kind) {
        return switch (kind) {
            case "CITIZEN" -> ActorType.CITIZEN;
            case "OFFICER" -> ActorType.OFFICER;
            case "REVIEWER" -> ActorType.REVIEWER;
            case "ADMIN" -> ActorType.ADMIN;
            case "DEPARTMENT" -> ActorType.DEPARTMENT;
            case "ANONYMOUS" -> ActorType.ANONYMOUS;
            // a caller was present (token validated) but its kind is not a known principal
            default -> ActorType.AUTHENTICATED;
        };
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
