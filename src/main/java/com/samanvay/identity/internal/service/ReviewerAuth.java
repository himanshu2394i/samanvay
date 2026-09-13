package com.samanvay.identity.internal.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
class ReviewerAuth {

    boolean isReviewer() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            String roles = sra.getRequest().getHeader("X-Roles");
            return roles != null && roles.contains("IDENTITY_REVIEWER");
        }
        return false;
    }
}
