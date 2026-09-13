package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class ConnectorNotReadyException extends SamanvayException {
    public ConnectorNotReadyException(String ref) {
        super("Connector is not ready to publish: " + ref);
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "catalog/connector-not-ready";
    }

    @Override
    public String reason() {
        return "CONNECTOR_NOT_READY";
    }
}
