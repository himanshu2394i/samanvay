package com.samanvay.notifications.api;

public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(String ref) {
        super(ref);
    }
}
