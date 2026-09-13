package com.samanvay.shared;

public abstract class SamanvayException extends RuntimeException {

    protected SamanvayException(String message) {
        super(message);
    }

    protected SamanvayException(String message, Throwable cause) {
        super(message, cause);
    }
}
