package com.samanvay.identity.internal.web;

import com.samanvay.identity.api.DepartmentCitizens;
import com.samanvay.identity.api.DepartmentLogin;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.NotFoundException;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A department portal acts as itself, for citizens who signed in with it. The department is the token's {@code department}
 * claim, never anything in a body. Role rule (DEPARTMENT only) lives in SecurityConfig.
 */
@RestController
@RequestMapping("/api/department")
class DepartmentIdentityController {

    private final DepartmentCitizens citizens;
    private final DepartmentLogin login;
    private final IdentityLinking linking;

    DepartmentIdentityController(DepartmentCitizens citizens, DepartmentLogin login, IdentityLinking linking) {
        this.citizens = citizens;
        this.login = login;
        this.linking = linking;
    }

    /** The citizen who just signed in at this department's portal; created on the first sign in. */
    @PostMapping("/citizens/resolve")
    DepartmentCitizens.Resolution resolve(@RequestBody ResolveBody body) {
        Caller caller = caller();
        InvalidRequestException.requireText(body.assertion(), "assertion");
        return citizens.resolve(caller.department(), body.assertion(), caller.subject());
    }

    /** Where to send the citizen's browser to log in at another department; it comes back to this department's own host. */
    @PostMapping("/links/start")
    LoginStart start(@RequestBody StartBody body) {
        Caller caller = caller();
        requireSignedInHere(caller, body.citizenId());
        return new LoginStart(login.startLoginFor(caller.department(), body.citizenId(), body.departmentCode(), body.returnTo()));
    }

    /** Saves the link proven by the other department's login; returns the surviving citizen (see {@link DepartmentCitizens#link}). */
    @PostMapping("/links")
    Linked link(@RequestBody LinkBody body) {
        Caller caller = caller();
        requireSignedInHere(caller, body.citizenId());
        InvalidRequestException.requireText(body.assertion(), "assertion");
        return new Linked(citizens.link(body.citizenId(), body.departmentCode(), body.assertion(), caller.subject()));
    }

    private static Caller caller() {
        Caller caller = Callers.require();
        if (caller.department() == null || caller.department().isBlank()) {
            throw new AccessDeniedException("token carries no department");
        }
        return caller;
    }

    private void requireSignedInHere(Caller caller, UUID citizenId) {
        // 404, not 403: 403 means "your role may not call this"; here the citizen simply is not one of this department's.
        if (citizenId == null || linking.activeLink(citizenId, caller.department()).isEmpty()) {
            throw new NotFoundException("citizen");
        }
    }

    record ResolveBody(String assertion) {}

    record StartBody(UUID citizenId, String departmentCode, String returnTo) {}

    record LoginStart(String loginUrl) {}

    record LinkBody(UUID citizenId, String departmentCode, String assertion) {}

    record Linked(UUID citizenId) {}
}
