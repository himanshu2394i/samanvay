package com.samanvay.identity.internal.service;

import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * No-auto-link layer 2 of 3 (service): confirm/reject require an authenticated
 * reviewer, and the reviewer identity recorded in audit is the token subject.
 *
 * <p>Reads only the Spring Security context, which is populated solely from a
 * validated JWT. Client-sent headers (the old {@code X-Roles}) and request
 * bodies (the old {@code reviewerId}) are not identity sources and are ignored.
 * Layer 1 is the route rule in shared.security.SecurityConfig; layer 3 is the
 * {@code chk_no_auto_probabilistic_link} CHECK in V40.
 */
@Component
class ReviewerAuth {

    static final String REVIEWER_AUTHORITY = "ROLE_REVIEWER";

    /** The authenticated reviewer's subject, or empty if the caller is not a reviewer. */
    Optional<String> currentReviewer() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        boolean reviewer = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(REVIEWER_AUTHORITY::equals);
        return reviewer ? Optional.of(auth.getName()) : Optional.empty();
    }
}
