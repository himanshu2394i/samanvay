package com.samanvay.identity.api;

import com.samanvay.shared.SamanvayException;

public class ReviewerRequiredException extends SamanvayException {
    public ReviewerRequiredException() {
        super("IDENTITY_REVIEWER role required");
    }

    @Override
    public int status() {
        return 403;
    }

    @Override
    public String problemType() {
        return "identity/reviewer-required";
    }

    @Override
    public String reason() {
        return "REVIEWER_REQUIRED";
    }
}
