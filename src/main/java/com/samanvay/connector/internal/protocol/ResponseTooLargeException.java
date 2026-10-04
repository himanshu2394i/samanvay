package com.samanvay.connector.internal.protocol;

/**
 * A department answered with more than the connector accepts (see {@link DeadlineHttp}). Not retried: asking again gets the same
 * answer. The runtime reports it as {@code FailureKind.RESPONSE_TOO_LARGE}.
 */
public class ResponseTooLargeException extends RuntimeException {

    public ResponseTooLargeException(String message) {
        super(message);
    }
}
