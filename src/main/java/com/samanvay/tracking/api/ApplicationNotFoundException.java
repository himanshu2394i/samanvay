package com.samanvay.tracking.api;

import com.samanvay.shared.SamanvayException;

public class ApplicationNotFoundException extends SamanvayException {
    public ApplicationNotFoundException(String ref) {
        super("Application not found: " + ref);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "tracking/not-found";
    }

    @Override
    public String reason() {
        return "APPLICATION_NOT_FOUND";
    }
}
