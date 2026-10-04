package com.samanvay.audit.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.audit.internal.service.DemoAuditTamper;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = SamanvayApplication.class)
@ActiveProfiles("demo")
@TestPropertySource(properties = {"samanvay.demo.tamper-endpoints=true", "samanvay.demo.chaos-endpoints=true"})
class AuditExplorerIT extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    DemoAuditTamper tamper;

    @Test
    void verifyPassesThenFailsAfterDemoTamper() {
        long head = audit.record(new AuditEntry(
                        ActorType.SYSTEM, "explorer-it", "PING", "s", null, null, null, null, Outcome.ALLOWED, null, Map.of()))
                .seq();
        assertThat(head).isGreaterThan(0);
        VerificationResult ok = audit.verify(head, head);
        assertThat(ok.valid()).isTrue();
        tamper.rewriteReason(head, "tampered-demo");
        try {
            VerificationResult failed = audit.verify(head, head);
            assertThat(failed.valid()).isFalse();
            assertThat(failed.failedAtSeq()).isEqualTo(head);
            assertThat(failed.reason()).contains("modified");
        } finally {
            // Shared Postgres container: restore so later full-chain checks are not order-dependent.
            tamper.rewriteReason(head, null);
        }
        assertThat(audit.verify(head, head).valid()).isTrue();
    }
}
