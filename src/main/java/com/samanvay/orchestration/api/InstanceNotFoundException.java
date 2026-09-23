package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;

public class InstanceNotFoundException extends SamanvayException {
    public InstanceNotFoundException() {
        super("Journey instance not found");
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "orchestration/instance-not-found";
    }

    @Override
    public String reason() {
        return "INSTANCE_NOT_FOUND";
    }
}
