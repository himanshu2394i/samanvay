package com.samanvay.consent.api;

public enum DenialReason {
    NO_ACTIVE_LINK,
    NO_CONSENT,
    CONSENT_EXPIRED("This permission has ended, so this department can no longer check this document."),
    CONSENT_REVOKED("You withdrew this permission, so this department can no longer check this document."),
    FREQUENCY_EXCEEDED,
    NO_POINTER,
    POINTER_EXPIRED,
    INSUFFICIENT_CLEARANCE,
    STALE_NOT_ACCEPTED,
    /** The purpose code is not an active catalog purpose. */
    UNKNOWN_PURPOSE;

    private final String plainMessage;

    DenialReason() {
        this(null);
    }

    DenialReason(String plainMessage) {
        this.plainMessage = plainMessage;
    }

    /** A sentence a citizen can read, for reasons that have one; otherwise {@code null}. */
    public String plainMessage() {
        return plainMessage;
    }
}
