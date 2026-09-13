package com.samanvay.identity.internal.web;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.Candidate;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.Profile;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.identity.api.ReviewFilter;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
class IdentityController {

    private final CitizenProfiles profiles;
    private final IdentityLinking linking;
    private final IdentityResolution resolution;

    IdentityController(CitizenProfiles profiles, IdentityLinking linking, IdentityResolution resolution) {
        this.profiles = profiles;
        this.linking = linking;
        this.resolution = resolution;
    }

    @PostMapping("/citizens")
    UUID register(@RequestBody ProfileDraft draft) {
        return profiles.register(draft);
    }

    @GetMapping("/citizens/{id}")
    Profile profile(@PathVariable UUID id) {
        return profiles.profile(id);
    }

    @PostMapping("/links")
    Link assertLink(@RequestBody LinkBody body) {
        return linking.assertLink(body.citizenId(), body.departmentCode(), body.localIdType(), body.localId(), new AuthProof(body.proof()));
    }

    @GetMapping("/citizens/{id}/links")
    List<Link> links(@PathVariable UUID id) {
        return linking.activeLinks(id);
    }

    @GetMapping("/review-queue")
    Page<Candidate> reviewQueue(Pageable pageable) {
        return resolution.reviewQueue(ReviewFilter.pending(), pageable);
    }

    @PostMapping("/candidates/{id}/confirm")
    Link confirm(@PathVariable UUID id, @RequestBody ReviewBody body) {
        return resolution.confirm(id, body.reviewerId(), body.note());
    }

    @PostMapping("/candidates/{id}/reject")
    void reject(@PathVariable UUID id, @RequestBody ReviewBody body) {
        resolution.reject(id, body.reviewerId(), body.note());
    }

    record LinkBody(UUID citizenId, String departmentCode, String localIdType, String localId, String proof) {}

    record ReviewBody(String reviewerId, String note) {}
}
