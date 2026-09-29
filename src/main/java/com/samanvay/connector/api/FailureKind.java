package com.samanvay.connector.api;

public enum FailureKind {
    TIMEOUT,
    BREAKER_OPEN,
    REMOTE_FAULT,
    RESPONSE_TOO_LARGE,
    GRANT_INVALID,
    /** The one-check claim was taken over while the call ran: the result was discarded. */
    CLAIM_LOST
}
