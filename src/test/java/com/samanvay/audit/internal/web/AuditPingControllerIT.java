package com.samanvay.audit.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = SamanvayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditPingControllerIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AuditService auditService;

    @Test
    void pingProducesChainedVerifiableEntry() {
        AuditRef ref = RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/internal/audit/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("subjectId", "phase0-citizen"))
                .retrieve()
                .body(AuditRef.class);

        assertThat(ref).isNotNull();
        assertThat(ref.seq()).isPositive();
        assertThat(ref.hash()).isNotEmpty();

        VerificationResult result = auditService.verify(ref.seq(), ref.seq());
        assertThat(result.valid()).isTrue();
    }
}
