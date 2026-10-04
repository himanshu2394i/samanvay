package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** The consent request was already answered (granted by the citizen or the department portal), so it cannot be granted again. */
public class ConsentRequestNotPendingException extends SamanvayException {

    public ConsentRequestNotPendingException() {
        super("This consent request has already been answered");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "consent/request-not-pending";
    }

    @Override
    public String reason() {
        return "CONSENT_REQUEST_NOT_PENDING";
    }
}
