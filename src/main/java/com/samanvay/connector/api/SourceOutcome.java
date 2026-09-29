package com.samanvay.connector.api;

import java.util.List;

/**
 * Typed result of one call to an external source. {@link Answered} carries the
 * source's answer. An answer can itself be negative (e.g. a CLOSED account), and
 * it is still an answer, not a failure. The three failures are the only ways a
 * call can fail.
 *
 * <p>Failures carry NO cause, exception, message or body text: only fixed codes
 * and field names. That keeps upstream text (which could quote personal data)
 * out of logs, audit rows and problem details by construction. Mapping to HTTP
 * statuses is the API layer's job (connector.internal.web.SourceOutcomeProblems).
 *
 * <p>{@code simulatorMarker} reports whether the response carried the simulator
 * marker. In simulator/sandbox mode it is reported only; in LIVE mode a marked
 * response is refused as {@link ReasonCode#MARKER_IN_LIVE_MODE} (see the
 * bank-check contract and the source mode-switch).
 */
public sealed interface SourceOutcome<T>
        permits SourceOutcome.Answered, SourceOutcome.SourceTimeout, SourceOutcome.SourceFault, SourceOutcome.RequestRejected {

    boolean simulatorMarker();

    record Answered<T>(T answer, boolean simulatorMarker) implements SourceOutcome<T> {}

    /** No response within the read timeout (so there is no marker to report). */
    record SourceTimeout<T>() implements SourceOutcome<T> {
        @Override
        public boolean simulatorMarker() {
            return false;
        }
    }

    record SourceFault<T>(ReasonCode reasonCode, boolean simulatorMarker) implements SourceOutcome<T> {}

    /** The source refused our request; {@code rejectedFields} are field NAMES only. */
    record RequestRejected<T>(List<String> rejectedFields, boolean simulatorMarker) implements SourceOutcome<T> {
        public RequestRejected {
            rejectedFields = List.copyOf(rejectedFields);
        }
    }

    enum ReasonCode {
        /** Connection refused/reset, DNS, TLS: no HTTP response at all. */
        CONNECTION_FAILED,
        /** HTTP 5xx. */
        SERVER_ERROR,
        /** HTTP 401/403: our credential was refused. */
        AUTH_REJECTED,
        /** A status the contract doesn't define for this call. */
        UNEXPECTED_STATUS,
        /** The body ended mid-document. */
        TRUNCATED_BODY,
        /** The body isn't JSON, or isn't a JSON object. */
        BAD_JSON,
        /** Valid JSON that breaks the contract (missing/unknown enum, broken invariant). */
        CONTRACT_VIOLATION,
        /** No credential in SecretStore for this source; the call was not made. */
        CREDENTIAL_MISSING,
        /** No adapter is registered for the requested source code. */
        NOT_CONFIGURED,
        /** A LIVE source returned the simulator marker: refused, audited and alarmed. */
        MARKER_IN_LIVE_MODE
    }
}
