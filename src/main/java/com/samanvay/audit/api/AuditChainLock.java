package com.samanvay.audit.api;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Whether the current transaction has appended to (and so holds the transaction-scoped advisory
 * lock of) the audit chain. The audit append marks it; code that is about to open a NEW
 * transaction to append (REQUIRES_NEW) checks it and fails fast instead of waiting forever on the
 * lock its own suspended outer transaction holds.
 *
 * <p>The flag is a transaction synchronization, so it belongs to exactly one transaction: it is
 * suspended with that transaction under REQUIRES_NEW and cleared when the transaction completes.
 */
public final class AuditChainLock {

    private AuditChainLock() {}

    /** Called by the audit append after taking the chain lock. */
    public static void markHeldByCurrentTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive() && !heldByCurrentTransaction()) {
            TransactionSynchronizationManager.registerSynchronization(new Held());
        }
    }

    public static boolean heldByCurrentTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(Held.class::isInstance);
    }

    private static final class Held implements TransactionSynchronization {}
}
