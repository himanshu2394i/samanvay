package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class UnknownTransformException extends SamanvayException {
    public UnknownTransformException(String fn) {
        super("Unknown mapping transform: " + fn);
    }

    @Override
    public String problemType() {
        return "catalog/unknown-transform";
    }

    @Override
    public String reason() {
        return "UNKNOWN_TRANSFORM";
    }
}
