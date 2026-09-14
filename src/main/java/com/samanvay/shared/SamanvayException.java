package com.samanvay.shared;

import java.util.Map;

public abstract class SamanvayException extends RuntimeException {

    protected SamanvayException(String message) {
        super(message);
    }

    protected SamanvayException(String message, Throwable cause) {
        super(message, cause);
    }

    public int status() {
        return 400;
    }

    public String title() {
        return getClass().getSimpleName();
    }

    public String problemType() {
        return "error";
    }

    public String reason() {
        return getClass().getSimpleName();
    }

    public Map<String, Object> properties() {
        return Map.of();
    }
}
