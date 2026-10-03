package com.samanvay.orchestration.internal.web;

import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.DepartmentLinkNeed;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.NotFoundException;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.DepartmentScope;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a department portal needs to draw its "connect accounts" step: for a journey it runs and a citizen who signed in with it, which
 * departments are connected and whether consent is already active. Role rule (DEPARTMENT only) lives in SecurityConfig.
 */
@RestController
@RequestMapping("/api/department/journeys")
class DepartmentJourneyController {

    private final IdentityLinking linking;
    private final ConsentService consents;
    private final JourneyCatalog journeys;
    private final PurposeCatalog purposes;
    private final DepartmentScope scope;

    DepartmentJourneyController(
            IdentityLinking linking, ConsentService consents, JourneyCatalog journeys, PurposeCatalog purposes, DepartmentScope scope) {
        this.linking = linking;
        this.consents = consents;
        this.journeys = journeys;
        this.purposes = purposes;
        this.scope = scope;
    }

    @GetMapping("/{code}/readiness")
    Readiness readiness(@PathVariable String code, @RequestParam UUID citizenId) {
        Caller caller = Callers.require();
        if (caller.department() == null || caller.department().isBlank()) {
            throw new AccessDeniedException("token carries no department");
        }
        scope.requireRuns(code);
        if (linking.activeLink(citizenId, caller.department()).isEmpty()) {
            throw new NotFoundException("citizen"); // not one of this department's citizens
        }
        var journey = journeys.byCode(code);
        Purpose purpose = purposes.byCode(journey.policy().purpose())
                .orElseThrow(() -> new InvalidRequestException("journey has no usable purpose"));
        boolean consentActive = !purpose.dataCategories().isEmpty() && purpose.dataCategories().stream()
                .allMatch(category -> consents
                        .find(new RequesterRef(caller.department()), new SubjectRef(citizenId), new DataCategory(category), new PurposeCode(purpose.code()))
                        .filter(c -> "ACTIVE".equals(c.status()))
                        .isPresent());
        return new Readiness(code, linking.connectAccounts(citizenId, code).departments(), consentActive);
    }

    record Readiness(String journeyCode, List<DepartmentLinkNeed> departments, boolean consentActive) {}
}
