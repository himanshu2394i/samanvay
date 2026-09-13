package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

public class IllegalHostException extends SamanvayException {
    public IllegalHostException(String host) {
        super("Host is not allowlisted: " + host);
    }

    @Override
    public String problemType() {
        return "catalog/illegal-host";
    }

    @Override
    public String reason() {
        return "ILLEGAL_HOST";
    }
}
