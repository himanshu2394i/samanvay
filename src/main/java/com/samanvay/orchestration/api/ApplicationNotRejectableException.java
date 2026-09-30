package com.samanvay.orchestration.api;

import com.samanvay.shared.SamanvayException;
import java.util.UUID;

/** The application is already terminal, so an officer cannot reject it (APPROVED/REJECTED/CLOSED): 409. */
public class ApplicationNotRejectableException extends SamanvayException {

    public ApplicationNotRejectableException(UUID instanceId, String currentStatus) {
        super("Application " + instanceId + " is " + currentStatus
                + "; only a non-terminal application can be rejected");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "orchestration/application-not-rejectable";
    }

    @Override
    public String reason() {
        return "APPLICATION_NOT_REJECTABLE";
    }
}
