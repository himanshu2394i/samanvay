package com.samanvay.connector.api;

public enum FailureKind {
    TIMEOUT,
    BREAKER_OPEN,
    REMOTE_FAULT,
    RESPONSE_TOO_LARGE,
    GRANT_INVALID
}
