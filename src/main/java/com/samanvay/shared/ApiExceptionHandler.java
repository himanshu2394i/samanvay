package com.samanvay.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(SamanvayException.class)
    ProblemDetail handle(SamanvayException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.status());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setTitle(ex.title());
        detail.setType(java.net.URI.create("https://samanvay.dev/problems/" + ex.problemType()));
        detail.setInstance(java.net.URI.create(request.getRequestURI()));
        detail.setProperty("reason", ex.reason());
        if (ex.status() == 401 || ex.status() == 403) {
            // so the refused-call audit entry names the specific reason
            request.setAttribute(com.samanvay.shared.security.ApiAccessRefused.REASON_ATTRIBUTE, ex.reason());
            if (ex.audited()) {
                // the module already wrote this refusal's audit entry; one row per refusal
                request.setAttribute(com.samanvay.shared.security.ApiAccessRefused.AUDITED_ATTRIBUTE, Boolean.TRUE);
            }
        }
        ex.properties().forEach(detail::setProperty);
        return detail;
    }
}
