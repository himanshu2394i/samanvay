package com.samanvay.connector.api;

import com.samanvay.shared.SamanvayException;

public class UnknownTransformException extends SamanvayException {
    public UnknownTransformException(String fn) {
        super("Unknown transform: " + fn);
    }

    @Override
    public String problemType() {
        return "connector/unknown-transform";
    }

    @Override
    public String reason() {
        return "UNKNOWN_TRANSFORM";
    }
}
