package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** A purpose that needs a prior-year award was requested for a citizen who has none. */
public class NoPriorAwardException extends SamanvayException {

    public NoPriorAwardException(String purposeCode) {
        super("Purpose " + purposeCode + " needs an approved award from the prior year, and there is none");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "consent/no-prior-award";
    }

    @Override
    public String reason() {
        return "NO_PRIOR_AWARD";
    }
}
