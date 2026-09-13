package com.samanvay.audit.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class AuditEntryRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    AuditService auditService;

    @Test
    void chainOfFiveVerifies() {
        long first = 0;
        long last = 0;
        for (int i = 1; i <= 5; i++) {
            var ref = auditService.record(entry("subject-" + i));
            if (i == 1) {
                first = ref.seq();
            }
            last = ref.seq();
        }
        assertThat(last - first).isEqualTo(4);
        VerificationResult result = auditService.verify(first, last);
        assertThat(result.valid()).isTrue();
        assertThat(result.fromSeq()).isEqualTo(first);
        assertThat(result.toSeq()).isEqualTo(last);
    }

    @Test
    void nestedMetaSurvivesJsonbRoundtripAndVerifies() {
        var ref = auditService.record(new AuditEntry(
                ActorType.SYSTEM,
                "repo-it",
                "PING",
                "nested-meta",
                null,
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of(
                        "count",
                        3,
                        "nested",
                        Map.of("b", 1, "a", 2, "label", "x"))));

        VerificationResult result = auditService.verify(ref.seq(), ref.seq());
        assertThat(result.valid()).isTrue();
    }

    private static AuditEntry entry(String subject) {
        return new AuditEntry(
                ActorType.SYSTEM,
                "repo-it",
                "PING",
                subject,
                null,
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of("n", subject));
    }
}
