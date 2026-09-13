package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

public class ConsentNotFoundException extends SamanvayException {
    public ConsentNotFoundException() {
        super("Consent not found");
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "consent/not-found";
    }

    @Override
    public String reason() {
        return "CONSENT_NOT_FOUND";
    }
}
