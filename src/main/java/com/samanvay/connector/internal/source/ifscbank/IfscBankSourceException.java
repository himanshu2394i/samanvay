package com.samanvay.connector.internal.source.ifscbank;

/** A call that produced no usable answer. Retry/breaker/badge policy is decided by the caller, not here. */
public class IfscBankSourceException extends RuntimeException {

    public enum Failure {
        /** No response within the read timeout. */
        TIMEOUT,
        /** 5xx, or the connection failed. */
        REMOTE_FAULT,
        /** 2xx/4xx whose body is not the documented JSON. */
        MALFORMED_RESPONSE,
        /** 401/403: our credentials were refused. */
        AUTH_REJECTED,
        /** Any other status the spec doesn't define for this call. */
        UNEXPECTED_STATUS
    }

    private final Failure failure;
    private final boolean simulatorMarker;

    IfscBankSourceException(Failure failure, boolean simulatorMarker, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.simulatorMarker = simulatorMarker;
    }

    public Failure failure() {
        return failure;
    }

    /** Whether the failed response carried the simulator marker (see {@link IfscBankClient#MARKER_HEADER}). */
    public boolean simulatorMarker() {
        return simulatorMarker;
    }
}
