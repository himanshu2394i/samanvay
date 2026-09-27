package com.samanvay.consent.internal.web;

import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
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

    ConsentController(ConsentService consents, CitizenAccess citizenAccess) {
        this.consents = consents;
        this.citizenAccess = citizenAccess;
    }

    @PostMapping("/requests")
    ConsentRequest request(@RequestBody ConsentRequestDraft draft) {
        citizenAccess.requireMayActOn(draft.citizenId());
        return consents.request(draft);
    }

    @PostMapping("/requests/{id}/grant")
    ConsentArtifact grant(@PathVariable UUID id, @RequestBody GrantBody body) {
        citizenAccess.requireMayActOn(body.citizenId());
        String sessionProof = Callers.require().sessionId();
        if (sessionProof == null || sessionProof.isBlank()) {
            throw new AccessDeniedException("token has no jti; cannot prove the granting session");
        }
        return consents.grant(id, body.citizenId(), new AuthProof(sessionProof));
    }

    @PostMapping("/{id}/revoke")
    void revoke(@PathVariable UUID id, @RequestBody RevokeBody body) {
        citizenAccess.requireMayActOn(body.citizenId());
        consents.revoke(id, body.citizenId(), body.reason());
    }

    @GetMapping("/citizens/{citizenId}")
    List<ConsentArtifact> forCitizen(@PathVariable UUID citizenId) {
        citizenAccess.requireMayActOn(citizenId);
        return consents.forCitizen(citizenId);
    }

    record GrantBody(UUID citizenId) {}

    record RevokeBody(UUID citizenId, String reason) {}
}
