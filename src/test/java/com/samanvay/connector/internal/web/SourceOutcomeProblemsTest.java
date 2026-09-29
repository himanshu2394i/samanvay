package com.samanvay.connector.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.ReasonCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class SourceOutcomeProblemsTest {

    @Test
    void timeout_is_504() {
        ProblemDetail p = SourceOutcomeProblems.toProblem("ifsc-bank", new SourceOutcome.SourceTimeout<>());
        assertThat(p.getStatus()).isEqualTo(504);
        assertThat(p.getType()).hasToString(SourceOutcomeProblems.TYPE);
        assertThat(p.getProperties()).containsEntry("outcome", "SourceTimeout").containsEntry("source", "ifsc-bank");
        assertThat(p.getDetail()).isNull();
    }

    @Test
    void fault_is_502_with_its_reason_code() {
        ProblemDetail p = SourceOutcomeProblems.toProblem("ifsc-bank", new SourceOutcome.SourceFault<>(ReasonCode.TRUNCATED_BODY, true));
        assertThat(p.getStatus()).isEqualTo(502);
        assertThat(p.getProperties()).containsEntry("outcome", "SourceFault").containsEntry("reasonCode", "TRUNCATED_BODY");
    }

    @Test
    void rejected_is_422_with_field_names() {
        ProblemDetail p = SourceOutcomeProblems.toProblem("ifsc-bank", new SourceOutcome.RequestRejected<>(List.of("ifsc"), false));
        assertThat(p.getStatus()).isEqualTo(422);
        assertThat(p.getProperties()).containsEntry("outcome", "RequestRejected").containsEntry("rejectedFields", List.of("ifsc"));
    }

    @Test
    void an_answer_is_not_mapped() {
        assertThatThrownBy(() -> SourceOutcomeProblems.toProblem("ifsc-bank", new SourceOutcome.Answered<>("x", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
