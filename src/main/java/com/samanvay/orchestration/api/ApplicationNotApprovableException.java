package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;
import java.util.UUID;

/** The application is not in a state an officer can approve from (only VERIFIED is): 409. */
public class ApplicationNotApprovableException extends SamanvayException {

    public ApplicationNotApprovableException(UUID instanceId, String currentStatus) {
        super("Application " + instanceId + " is " + currentStatus + "; only a VERIFIED application can be approved");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "orchestration/application-not-approvable";
    }

    @Override
    public String reason() {
        return "APPLICATION_NOT_APPROVABLE";
    }
}
