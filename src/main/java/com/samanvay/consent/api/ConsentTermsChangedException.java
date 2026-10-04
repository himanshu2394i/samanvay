package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** The purpose's data types, categories, wording or validity changed in the catalog after the citizen was asked: ask again. */
public class ConsentTermsChangedException extends SamanvayException {

    public ConsentTermsChangedException() {
        super("The consent terms changed since this request was made; ask the citizen again");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "consent/terms-changed";
    }

    @Override
    public String reason() {
        return "CONSENT_TERMS_CHANGED";
    }
}
