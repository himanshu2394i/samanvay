package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class ConnectorNotFoundException extends SamanvayException {
    public ConnectorNotFoundException(String ref) {
        super("Connector not found: " + ref);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "catalog/connector-not-found";
    }

    @Override
    public String reason() {
        return "CONNECTOR_NOT_FOUND";
    }
}
