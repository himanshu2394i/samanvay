package com.samanvay.consent.internal.web;

import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentWording;
import com.samanvay.consent.internal.service.DepartmentConsentService;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A department portal collects consent on its own pages. The department is the token's {@code department} claim, never a body field.
 * Role rule (DEPARTMENT only) lives in SecurityConfig.
 */
@RestController
@RequestMapping("/api/department/consents")
class DepartmentConsentController {

    private final DepartmentConsentService service;

    DepartmentConsentController(DepartmentConsentService service) {
        this.service = service;
    }

    /** The exact wording to show the citizen, and the nonce the department's signed statement must carry. */
    @PostMapping("/requests")
    ConsentWording request(@RequestBody AskBody body) {
        Caller caller = caller();
        InvalidRequestException.requirePresent(body.citizenId(), "citizenId");
        InvalidRequestException.requireText(body.journeyCode(), "journeyCode");
        return service.request(caller.department(), body.citizenId(), body.journeyCode(), caller.principal());
    }

    /** Grants the consent from the department's signed statement of the citizen's confirmation. */
    @PostMapping
    ConsentArtifact grant(@RequestBody StatementBody body) {
        Caller caller = caller();
        InvalidRequestException.requireText(body.statement(), "statement");
        return service.grant(caller.department(), body.statement(), caller.principal());
    }

    private static Caller caller() {
        Caller caller = Callers.require();
        if (caller.department() == null || caller.department().isBlank()) {
            throw new AccessDeniedException("token carries no department");
        }
        return caller;
    }

    record AskBody(UUID citizenId, String journeyCode) {}

    record StatementBody(String statement) {}
}
