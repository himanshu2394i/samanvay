package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** A permission request named a purpose code the catalog does not know (or has retired). */
public class UnknownPurposeException extends SamanvayException {
    public UnknownPurposeException(String code) {
        super("Unknown purpose code: " + code);
    }

    @Override
    public String problemType() {
        return "consent/unknown-purpose";
    }

    @Override
    public String reason() {
        return "UNKNOWN_PURPOSE";
    }
}
