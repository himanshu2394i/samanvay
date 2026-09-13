package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;
import java.util.UUID;

public class InvalidGrantException extends SamanvayException {
    public InvalidGrantException(UUID grantId, String detail) {
        super("Invalid grant " + grantId + ": " + detail);
    }

    public InvalidGrantException(UUID grantId, String detail, Throwable cause) {
        super("Invalid grant " + grantId + ": " + detail, cause);
    }

    @Override
    public int status() {
        return 403;
    }

    @Override
    public String problemType() {
        return "consent/invalid-grant";
    }

    @Override
    public String reason() {
        return "INVALID_GRANT";
    }
}
