package com.samanvay.consent.api;

public enum DenialReason {
    NO_ACTIVE_LINK,
    NO_CONSENT,
    CONSENT_EXPIRED,
    CONSENT_REVOKED,
    FREQUENCY_EXCEEDED,
    NO_POINTER,
    POINTER_EXPIRED,
    INSUFFICIENT_CLEARANCE,
    STALE_NOT_ACCEPTED,
    /** The purpose code is not an active catalog purpose. */
    UNKNOWN_PURPOSE,
    /** The consent allows one check per document per application, and it has been used (or is in flight). */
    CHECK_ALREADY_USED,
    /** The consent allows one check per application, but the request names no application. */
    APPLICATION_REQUIRED
}
