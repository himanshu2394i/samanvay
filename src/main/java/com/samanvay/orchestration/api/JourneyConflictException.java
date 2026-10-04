package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;

/**
 * The journey or application is in a state that refuses this call (409): starting a journey that is not published or
 * that the citizen already has open, cancelling an approved application.
 */
public class JourneyConflictException extends SamanvayException {

    private final String reason;

    public JourneyConflictException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "orchestration/journey-conflict";
    }

    @Override
    public String reason() {
        return reason;
    }
}
