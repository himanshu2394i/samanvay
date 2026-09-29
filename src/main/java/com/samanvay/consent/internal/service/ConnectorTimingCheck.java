package com.samanvay.consent.internal.service;

import java.time.Duration;

/**
 * Boot check: a PENDING one-check claim may only be treated as stale once the connector exchange
 * that owns it must be over. The exchange is bounded by {@code samanvay.connector.total-timeout},
 * which spans every retry attempt; a deadline overrun is not retried, so the only possible
 * overshoot is one retry backoff that began just before the deadline. Startup fails unless
 *
 * <pre>stale window  &gt;  total-timeout + (max-attempts &gt; 1 ? retry wait : 0) + stale-margin</pre>
 */
final class ConnectorTimingCheck {

    private ConnectorTimingCheck() {}

    static Duration worstCaseExchange(Duration totalTimeout, int retryMaxAttempts, Duration retryWait) {
        return retryMaxAttempts > 1 ? totalTimeout.plus(retryWait) : totalTimeout;
    }

    static void validate(
            Duration staleWindow, Duration totalTimeout, Duration margin, int retryMaxAttempts, Duration retryWait) {
        Duration required = worstCaseExchange(totalTimeout, retryMaxAttempts, retryWait).plus(margin);
        if (staleWindow.compareTo(required) <= 0) {
            throw new IllegalStateException("consent usage stale window " + staleWindow
                    + " (2 x samanvay.connector.timeout) must be greater than the worst-case connector exchange "
                    + worstCaseExchange(totalTimeout, retryMaxAttempts, retryWait)
                    + " (samanvay.connector.total-timeout" + (retryMaxAttempts > 1 ? " + one retry wait" : "")
                    + ") plus samanvay.connector.stale-margin " + margin
                    + ": otherwise a claim could be taken over while its department call is still running");
        }
    }
}
