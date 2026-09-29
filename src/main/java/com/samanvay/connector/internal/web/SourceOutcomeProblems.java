package com.samanvay.connector.internal.web;

import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.Answered;
import com.samanvay.connector.api.SourceOutcome.RequestRejected;
import com.samanvay.connector.api.SourceOutcome.SourceFault;
import com.samanvay.connector.api.SourceOutcome.SourceTimeout;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * API-layer mapping of a failed {@link SourceOutcome} to an RFC 9457 problem:
 * SourceTimeout → 504, SourceFault → 502, RequestRejected → 422. Every value is a
 * fixed string, a code or a field name, because the outcome carries nothing else.
 */
public final class SourceOutcomeProblems {

    static final String TYPE = "https://samanvay.dev/problems/source-unavailable";

    private SourceOutcomeProblems() {}

    public static ProblemDetail toProblem(String sourceCode, SourceOutcome<?> outcome) {
        ProblemDetail problem = switch (outcome) {
            case Answered<?> a -> throw new IllegalArgumentException("an answer is not a failure");
            case SourceTimeout<?> t -> base(HttpStatus.GATEWAY_TIMEOUT, "Source timed out", "SourceTimeout");
            case SourceFault<?> f -> {
                ProblemDetail p = base(HttpStatus.BAD_GATEWAY, "Source fault", "SourceFault");
                p.setProperty("reasonCode", f.reasonCode().name());
                yield p;
            }
            case RequestRejected<?> r -> {
                ProblemDetail p = base(HttpStatus.UNPROCESSABLE_CONTENT, "Request rejected by source", "RequestRejected");
                p.setProperty("rejectedFields", r.rejectedFields());
                yield p;
            }
        };
        problem.setProperty("source", sourceCode);
        return problem;
    }

    private static ProblemDetail base(HttpStatus status, String title, String outcome) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE));
        problem.setTitle(title);
        problem.setProperty("outcome", outcome);
        return problem;
    }
}
