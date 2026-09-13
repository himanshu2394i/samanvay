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

@SpringBootTest(classes = SamanvayApplication.class)
class AuditExplorerIT extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    DemoAuditTamper tamper;

    @Test
    void verifyPassesThenFailsAfterDemoTamper() {
        audit.record(new AuditEntry(
                ActorType.SYSTEM, "explorer-it", "PING", "s", null, null, null, null, Outcome.ALLOWED, null, Map.of()));
        long head = audit.headSeq();
        assertThat(head).isGreaterThan(0);
        VerificationResult ok = audit.verify(head, head);
        assertThat(ok.valid()).isTrue();
        tamper.rewriteReason(head, "tampered-demo");
        VerificationResult failed = audit.verify(head, head);
        assertThat(failed.valid()).isFalse();
        assertThat(failed.failedAtSeq()).isEqualTo(head);
        assertThat(failed.reason()).contains("modified");
    }
}
