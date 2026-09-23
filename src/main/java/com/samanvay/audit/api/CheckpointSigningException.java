package com.samanvay.audit.api;

import com.samanvay.shared.SamanvayException;

public class CheckpointSigningException extends SamanvayException {

    public CheckpointSigningException(String message) {
        super(message);
    }

    public CheckpointSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
