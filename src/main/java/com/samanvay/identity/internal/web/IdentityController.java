package com.samanvay.identity.internal.web;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.Candidate;
import com.samanvay.identity.api.CitizenMatch;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ConnectAccounts;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProviderInfo;
import com.samanvay.identity.api.Profile;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.identity.api.ReviewFilter;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
class IdentityController {

    private final CitizenProfiles profiles;
    private final IdentityLinking linking;
    private final IdentityResolution resolution;
    private final CitizenAccess citizenAccess;

    IdentityController(
            CitizenProfiles profiles,
            IdentityLinking linking,
            IdentityResolution resolution,
            CitizenAccess citizenAccess) {
        this.profiles = profiles;
        this.linking = linking;
        this.resolution = resolution;
        this.citizenAccess = citizenAccess;
    }

    /** A citizen token self-registers (bound to its subject, idempotent); an officer registers on someone's behalf. */
    @PostMapping("/citizens")
    UUID register(@RequestBody ProfileDraft draft) {
        InvalidRequestException.requireText(draft.nameLatin(), "nameLatin");
        InvalidRequestException.requirePresent(draft.dob(), "dob");
        InvalidRequestException.requireText(draft.dobPrecision(), "dobPrecision");
        Caller caller = Callers.require();
        return caller.isCitizen() ? profiles.registerSelf(draft, caller.subject()) : profiles.register(draft);
    }

    /** Officer "Find a citizen": literal route, wins over {id}; gated OFFICER-only in SecurityConfig. */
    @GetMapping("/citizens/search")
    List<CitizenMatch> search(@RequestParam String q) {
        return profiles.search(q);
    }

    @GetMapping("/citizens/{id}")
    Profile profile(@PathVariable UUID id) {
        citizenAccess.requireMayActOn(id);
        return profiles.profile(id);
    }

    @GetMapping("/proof-providers")
    List<LinkProofProviderInfo> proofProviders() {
        return linking.availableProofProviders();
    }

    @PostMapping("/links")
    Link assertLink(@RequestBody LinkBody body) {
        citizenAccess.requireMayActOn(body.citizenId());
        return linking.assertLink(
                body.citizenId(),
                body.departmentCode(),
                body.localIdType(),
                body.localId(),
                new AuthProof(parseProvider(body.provider()), body.proof()));
    }

    @GetMapping("/citizens/{id}/links")
    List<Link> links(@PathVariable UUID id) {
        citizenAccess.requireMayActOn(id);
        return linking.activeLinks(id);
    }

    @GetMapping("/citizens/{id}/connect-accounts")
    ConnectAccounts connectAccounts(@PathVariable UUID id, @RequestParam String journeyCode) {
        citizenAccess.requireMayActOn(id);
        return linking.connectAccounts(id, journeyCode);
    }

    @GetMapping("/review-queue")
    Page<Candidate> reviewQueue(Pageable pageable) {
        return resolution.reviewQueue(ReviewFilter.pending(), pageable);
    }

    /** The reviewer is the token subject; a {@code reviewerId} in the body is not read. */
    @PostMapping("/candidates/{id}/confirm")
    Link confirm(@PathVariable UUID id, @RequestBody(required = false) ReviewBody body) {
        return resolution.confirm(id, body == null ? null : body.note());
    }

    @PostMapping("/candidates/{id}/reject")
    void reject(@PathVariable UUID id, @RequestBody(required = false) ReviewBody body) {
        resolution.reject(id, body == null ? null : body.note());
    }

    record LinkBody(
            UUID citizenId, String departmentCode, String localIdType, String localId, String provider, String proof) {}

    /** Only the note is read. Unknown properties (e.g. a legacy reviewerId) are ignored. */
    record ReviewBody(String note) {}

    private static LinkProofKind parseProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new LinkProofInvalidException();
        }
        try {
            return LinkProofKind.valueOf(provider);
        } catch (IllegalArgumentException ex) {
            throw new LinkProofInvalidException();
        }
    }
}
