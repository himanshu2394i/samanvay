package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class ConnectorNotReadyException extends SamanvayException {
    public ConnectorNotReadyException(String ref) {
        super("Connector is not ready to publish: " + ref);
    }

    /** @param why what is missing, for the admin to act on */
    public ConnectorNotReadyException(String ref, String why) {
        super("Connector " + ref + " is not ready to publish: " + why);
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
