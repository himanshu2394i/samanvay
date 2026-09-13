package com.samanvay.notifications.api;

public class UnresolvedTemplatePlaceholderException extends RuntimeException {
    public UnresolvedTemplatePlaceholderException(String templateRef) {
        super(templateRef);
    }
}
