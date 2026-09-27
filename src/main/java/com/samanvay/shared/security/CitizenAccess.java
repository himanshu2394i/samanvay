package com.samanvay.shared.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Own-record rule for citizen callers: a CITIZEN token may only act on the
 * citizen record bound to its own subject. Staff and department callers have
 * already been filtered by the route rules in {@link SecurityConfig} and pass.
 *
 * <p>Throws Spring Security's {@link AccessDeniedException}, which the filter
 * chain turns into the standard 403 ProblemDetail (and an audit entry).
 */
@Component
public class CitizenAccess {

    private final CitizenOwnership ownership;

    CitizenAccess(CitizenOwnership ownership) {
        this.ownership = ownership;
    }

    public void requireMayActOn(UUID citizenId) {
        Caller caller = Callers.require();
        if (!caller.isCitizen()) {
            return;
        }
        if (citizenId == null || !ownership.isBoundTo(citizenId, caller.subject())) {
            throw new AccessDeniedException("citizens may only act on their own record");
        }
    }
}
