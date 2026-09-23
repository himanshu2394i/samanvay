package com.samanvay.consent.internal.web;

import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/consent")
class ConsentController {

    private final ConsentService consents;

    ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @PostMapping("/requests")
    ConsentRequest request(@RequestBody ConsentRequestDraft draft) {
        return consents.request(draft);
    }

    @PostMapping("/requests/{id}/grant")
    ConsentArtifact grant(
            @PathVariable UUID id,
            @RequestBody GrantBody body,
            @RequestHeader(value = "X-Auth-Jti", defaultValue = "stub-session") String jti) {
        return consents.grant(id, body.citizenId(), new AuthProof(jti));
    }

    @PostMapping("/{id}/revoke")
    void revoke(@PathVariable UUID id, @RequestBody RevokeBody body) {
        consents.revoke(id, body.citizenId(), body.reason());
    }

    @GetMapping("/citizens/{citizenId}")
    List<ConsentArtifact> forCitizen(@PathVariable UUID citizenId) {
        return consents.forCitizen(citizenId);
    }

    record GrantBody(UUID citizenId) {}

    record RevokeBody(UUID citizenId, String reason) {}
}
