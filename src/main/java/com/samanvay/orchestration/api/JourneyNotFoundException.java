package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;

public class JourneyNotFoundException extends SamanvayException {
    public JourneyNotFoundException(String code) {
        super("Journey not found: " + code);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "orchestration/journey-not-found";
    }

    @Override
    public String reason() {
        return "JOURNEY_NOT_FOUND";
    }
}
