package com.samanvay.audit.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = SamanvayApplication.class)
@AutoConfigureMockMvc
class AuditPingControllerIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditService auditService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void pingProducesChainedVerifiableEntry() throws Exception {
        String body = mvc.perform(post("/internal/audit/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"phase0-citizen\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        long seq = json.get("seq").asLong();
        assertThat(seq).isPositive();
        assertThat(json.get("hash").isEmpty()).isFalse();

        VerificationResult result = auditService.verify(seq, seq);
        assertThat(result.valid()).isTrue();
    }
}
