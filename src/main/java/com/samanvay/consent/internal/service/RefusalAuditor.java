package com.samanvay.consent.internal.service;

import com.samanvay.audit.api.AuditChainLock;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends a consent refusal in its OWN transaction (REQUIRES_NEW, the same
 * {@link AuditService#record} append path), so the entry survives the refused
 * request's rollback.
 *
 * <p>Only call it before the surrounding transaction has appended anything to
 * the audit chain: the chain's advisory lock is transaction-scoped, and a
 * suspended outer transaction holding it would block this one forever. That is
 * checked, not just documented: if the calling transaction already holds the
 * chain ({@link AuditChainLock}, set by the audit append), {@link #record}
 * throws at once instead of deadlocking.
 */
@Component
class RefusalAuditor {

    private final AuditService audit;
    private final TransactionTemplate ownTransaction;

    RefusalAuditor(AuditService audit, PlatformTransactionManager transactions) {
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void record(AuditEntry entry) {
        if (AuditChainLock.heldByCurrentTransaction()) {
            throw new IllegalStateException("refusal audit needs its own transaction, but the calling transaction"
                    + " already appended to the audit chain and holds its lock: this would deadlock");
        }
        ownTransaction.executeWithoutResult(tx -> audit.record(entry));
    }
}
