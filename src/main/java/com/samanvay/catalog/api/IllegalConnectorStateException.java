package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class IllegalConnectorStateException extends SamanvayException {
    public IllegalConnectorStateException(String ref, String detail) {
        super(ref + ": " + detail);
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "catalog/illegal-connector-state";
    }

    @Override
    public String reason() {
        return "ILLEGAL_CONNECTOR_STATE";
    }
}
