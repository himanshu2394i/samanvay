package com.samanvay.connector.internal.protocol;

import java.net.http.HttpTimeoutException;

/**
 * The total connector deadline ran out. The in-flight HTTP exchange has been cancelled; nothing
 * that arrives later is used. Not retried (see {@code ResilienceRegistries}): the deadline already
 * covers every attempt. The cause is an {@link HttpTimeoutException}, so callers classify it as a
 * timeout.
 */
public class ExchangeDeadlineExceededException extends RuntimeException {

    public ExchangeDeadlineExceededException(String message) {
        super(message, new HttpTimeoutException(message));
    }
}
