package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

public class GrantSigningException extends SamanvayException {
    public GrantSigningException(Throwable cause) {
        super("Grant signing key unavailable", cause);
    }

    @Override
    public int status() {
        return 503;
    }

    @Override
    public String problemType() {
        return "consent/grant-signing";
    }

    @Override
    public String reason() {
        return "GRANT_SIGNING_FAILED";
    }
}
