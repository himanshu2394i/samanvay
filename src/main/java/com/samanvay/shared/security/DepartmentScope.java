package com.samanvay.shared.security;

import com.samanvay.shared.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * A department client sees and acts only on journeys it runs (the journey's requester is the token's {@code department} claim).
 * Every check passes for callers that are not department clients: their own route rules apply.
 */
@Component
public class DepartmentScope {

    private final JourneyRequesters requesters;

    DepartmentScope(JourneyRequesters requesters) {
        this.requesters = requesters;
    }

    public boolean isDepartmentCaller() {
        return Callers.require().isDepartmentClient();
    }

    /** True when the caller may see this journey's data: not a department client, or the journey is one it runs. */
    public boolean mayRead(String journeyCode) {
        Caller caller = Callers.require();
        return !caller.isDepartmentClient() || runs(caller, journeyCode);
    }

    /** 404 (not 403) so a department learns nothing about another department's applications. */
    public void requireReadable(String journeyCode) {
        if (!mayRead(journeyCode)) {
            throw new NotFoundException("application");
        }
    }

    /** 403: acting (starting, asking consent) for a journey the caller's department does not run. */
    public void requireRuns(String journeyCode) {
        Caller caller = Callers.require();
        if (caller.isDepartmentClient() && !runs(caller, journeyCode)) {
            throw new AccessDeniedException("this department does not run " + journeyCode);
        }
    }

    private boolean runs(Caller caller, String journeyCode) {
        return caller.department() != null && requesters.requesterOf(journeyCode).filter(caller.department()::equals).isPresent();
    }
}
