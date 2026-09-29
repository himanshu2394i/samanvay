package com.samanvay.shared;

import java.util.Map;

public abstract class SamanvayException extends RuntimeException {

    private boolean audited;

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

    /**
     * Marks this refusal as already written to the audit chain by the module that raised it, so
     * the generic refused-call entry ({@code API_FORBIDDEN}) is not written a second time.
     */
    public SamanvayException markAudited() {
        this.audited = true;
        return this;
    }

    public boolean audited() {
        return audited;
    }
}
