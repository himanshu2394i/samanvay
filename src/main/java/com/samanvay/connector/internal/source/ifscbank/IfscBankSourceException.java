package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.shared.SamanvayException;
import java.util.List;
import java.util.Map;

/**
 * A call that produced no usable answer. Retry, breaker and badge policy is
 * decided by the caller, not here. It is a {@link SamanvayException}, so it
 * renders through the shared problem-detail handler.
 *
 * <p>The message and properties hold only the failure kind, the HTTP status and
 * rejected field NAMES. Response bodies (and their parser errors, which can quote
 * body text) are never copied into the message and never attached as a cause.
 */
public class IfscBankSourceException extends SamanvayException {

    public enum Failure {
        /** No response within the read timeout. */
        TIMEOUT,
        /** 5xx, or the connection failed. */
        REMOTE_FAULT,
        /** A body that is not the contract's JSON, or that breaks a contract invariant. */
        MALFORMED_RESPONSE,
        /** 401/403: our credentials were refused. */
        AUTH_REJECTED,
        /** 400: the source rejected our request; see {@link #rejectedFields()}. */
        REQUEST_REJECTED,
        /** Any other status the contract doesn't define for this call. */
        UNEXPECTED_STATUS
    }

    private final Failure failure;
    private final boolean simulatorMarker;
    private final List<String> rejectedFields;

    IfscBankSourceException(Failure failure, boolean simulatorMarker, String message, Throwable cause) {
        this(failure, simulatorMarker, message, cause, List.of());
    }

    IfscBankSourceException(
            Failure failure, boolean simulatorMarker, String message, Throwable cause, List<String> rejectedFields) {
        super(message, cause);
        this.failure = failure;
        this.simulatorMarker = simulatorMarker;
        this.rejectedFields = List.copyOf(rejectedFields);
    }

    public Failure failure() {
        return failure;
    }

    /** Whether the failed response carried the simulator marker (see {@link IfscBankClient#MARKER_HEADER}). */
    public boolean simulatorMarker() {
        return simulatorMarker;
    }

    public List<String> rejectedFields() {
        return rejectedFields;
    }

    @Override
    public int status() {
        return switch (failure) {
            case TIMEOUT -> 504;
            case REQUEST_REJECTED -> 422;
            default -> 502;
        };
    }

    @Override
    public String problemType() {
        return "source-unavailable";
    }

    @Override
    public String reason() {
        return failure.name();
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("source", "ifsc-bank", "rejectedFields", rejectedFields);
    }
}
