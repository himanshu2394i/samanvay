package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.AuditChainLock;
import com.samanvay.audit.api.AuditService;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real audit append flags its transaction as holding the chain, and a refusal audit asked for
 * from that transaction throws at once instead of deadlocking on the chain's advisory lock.
 */
@SpringBootTest(classes = SamanvayApplication.class)
class AuditChainLockIT extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    RefusalAuditor refusals;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void appendFlagsTheTransactionAndARefusalAuditFromItFailsFast() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> tx.executeWithoutResult(status -> {
            assertThat(AuditChainLock.heldByCurrentTransaction()).isFalse();
            audit.record(RefusalAuditorTest.entry());
            assertThat(AuditChainLock.heldByCurrentTransaction()).as("set by the audit append").isTrue();

            TransactionTemplate inner = new TransactionTemplate(transactions);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.executeWithoutResult(s -> assertThat(AuditChainLock.heldByCurrentTransaction())
                    .as("bound to its own transaction: not visible in a new one").isFalse());

            assertThatThrownBy(() -> refusals.record(RefusalAuditorTest.entry()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deadlock");
            status.setRollbackOnly();
        }));
        assertThat(AuditChainLock.heldByCurrentTransaction()).as("cleared with the transaction").isFalse();
        // and outside any chain-holding transaction the refusal audit works
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> refusals.record(RefusalAuditorTest.entry()));
    }
}
