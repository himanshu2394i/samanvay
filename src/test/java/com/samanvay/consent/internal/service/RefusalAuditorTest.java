package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditChainLock;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A refusal audit (REQUIRES_NEW) from a transaction that already holds the audit chain fails fast. */
class RefusalAuditorTest {

    final AuditService audit = mock(AuditService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final RefusalAuditor auditor = new RefusalAuditor(audit, transactions);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void throwsAtOnceWhenTheCallingTransactionHoldsTheAuditChain() {
        TransactionSynchronizationManager.initSynchronization();
        AuditChainLock.markHeldByCurrentTransaction();
        assertThatThrownBy(() -> auditor.record(entry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadlock");
        verify(transactions, never()).getTransaction(any());
        verify(audit, never()).record(any());
    }

    @Test
    void appendsInItsOwnTransactionOtherwise() {
        TransactionSynchronizationManager.initSynchronization();
        auditor.record(entry());
        verify(audit).record(any());
    }

    static AuditEntry entry() {
        return new AuditEntry(ActorType.OFFICER, "off-1", "CONSENT_REQUEST_REFUSED", "c", "consent", "D", null, null,
                Outcome.DENIED, "X", Map.of());
    }
}
