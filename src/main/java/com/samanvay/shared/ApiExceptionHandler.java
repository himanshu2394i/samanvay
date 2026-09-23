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
        ex.properties().forEach(detail::setProperty);
        return detail;
    }
}
