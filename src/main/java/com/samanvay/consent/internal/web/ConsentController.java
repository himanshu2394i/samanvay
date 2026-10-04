package com.samanvay.consent.internal.web;

import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.ConsentNotFoundException;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.UnknownPurposeException;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.shared.NotFoundException;
import com.samanvay.shared.security.Caller;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
import com.samanvay.shared.security.CitizenOwnership;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role rules live in shared.security.SecurityConfig; this controller adds the
 * own-record rule (a citizen token only acts on its own citizenId) and takes
 * the consent session proof from the token's {@code jti}. Any {@code X-Auth-Jti}
 * header a client still sends is ignored.
 */
@RestController
@RequestMapping("/api/consent")
class ConsentController {

    private final ConsentService consents;
    private final CitizenAccess citizenAccess;
    private final PurposeCatalog purposes;
    private final CitizenOwnership ownership;
    private final IdentityLinking linking;

    ConsentController(
            ConsentService consents,
            CitizenAccess citizenAccess,
            PurposeCatalog purposes,
            CitizenOwnership ownership,
            IdentityLinking linking) {
        this.linking = linking;
        this.consents = consents;
        this.citizenAccess = citizenAccess;
        this.purposes = purposes;
        this.ownership = ownership;
    }

    /**
     * The body names only the citizen and the catalog purpose; requester, purpose
     * text and data categories are never read from it (extra fields are ignored).
     * The requester comes from the token: an officer or department client
     * requests as the department in its token's {@code department} claim, and
     * only under that department's purposes (else 403, audited). A citizen asks
     * on their own record for the purpose's own department.
     */
    @PostMapping("/requests")
    ConsentRequest request(@RequestBody RequestConsentBody body) {
        citizenAccess.requireMayActOn(body.citizenId());
        Purpose purpose = purposes.byCode(body.purposeCode())
                .filter(Purpose::active)
                .orElseThrow(() -> new UnknownPurposeException(body.purposeCode()));
        Caller caller = Callers.require();
        String requester;
        if (caller.isCitizen()) {
            requester = purpose.requesterDepartment();
        } else {
            requester = caller.department();
            if (requester == null || requester.isBlank()) {
                throw new AccessDeniedException("token carries no department; cannot request consent");
            }
            // A department client asks only for citizens linked to it (404, so it learns nothing about anyone else).
            // Staff officers are not department clients and keep asking for any citizen as their department. A purpose of
            // another department is left to consent's own refusal below (403, audited).
            if (caller.isDepartmentClient()
                    && requester.equals(purpose.requesterDepartment())
                    && (body.citizenId() == null || linking.activeLink(body.citizenId(), requester).isEmpty())) {
                throw new NotFoundException("citizen");
            }
        }
        return consents.request(new ConsentRequestDraft(body.citizenId(), requester, purpose.code(), caller.principal()));
    }

    @PostMapping("/requests/{id}/grant")
    ConsentArtifact grant(@PathVariable UUID id, @RequestBody GrantBody body) {
        citizenAccess.requireMayActOn(body.citizenId());
        Caller caller = Callers.require();
        String sessionProof = caller.sessionId();
        if (sessionProof == null || sessionProof.isBlank()) {
            throw new AccessDeniedException("token has no jti; cannot prove the granting session");
        }
        return consents.grant(id, body.citizenId(), new AuthProof(sessionProof), caller.principal());
    }

    /**
     * Older revoke route (citizen named in the body). A consent that is not the signed-in
     * citizen's own is a 404, whichever citizenId the body names, so a consent id reveals nothing.
     */
    @PostMapping("/{id}/revoke")
    void revoke(@PathVariable UUID id, @RequestBody RevokeBody body) {
        Caller caller = Callers.require();
        UUID owner = ownedByCaller(id, caller);
        if (!owner.equals(body.citizenId())) {
            throw new ConsentNotFoundException();
        }
        consents.revoke(id, owner, body.reason(), caller.principal());
    }

    /**
     * The signed-in citizen withdraws one of their own consents; the citizen comes from the
     * token, never the body. Someone else's consent (or no such consent) is a 404, so a
     * consent id reveals nothing. Afterwards every fetch under it is refused.
     */
    @PostMapping("/me/{id}/revoke")
    void revokeMine(@PathVariable UUID id, @RequestBody(required = false) MyRevokeBody body) {
        Caller caller = Callers.require();
        UUID owner = ownedByCaller(id, caller);
        consents.revoke(id, owner, body == null ? null : body.reason(), caller.principal());
    }

    /** The consent's citizen if that record is bound to the caller's token; otherwise 404. */
    private UUID ownedByCaller(UUID consentId, Caller caller) {
        return consents.ownerOf(consentId)
                .filter(citizen -> ownership.isBoundTo(citizen, caller.subject()))
                .orElseThrow(ConsentNotFoundException::new);
    }

    @GetMapping("/citizens/{citizenId}")
    List<ConsentArtifact> forCitizen(@PathVariable UUID citizenId) {
        citizenAccess.requireMayActOn(citizenId);
        return consents.forCitizen(citizenId);
    }

    record RequestConsentBody(UUID citizenId, String purposeCode) {}

    record GrantBody(UUID citizenId) {}

    record RevokeBody(UUID citizenId, String reason) {}

    record MyRevokeBody(String reason) {}
}
