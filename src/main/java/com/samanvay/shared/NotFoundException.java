package com.samanvay.shared;

/** The addressed resource does not exist: 404. */
public class NotFoundException extends SamanvayException {

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "not-found";
    }

    @Override
    public String reason() {
        return "NOT_FOUND";
    }
}
