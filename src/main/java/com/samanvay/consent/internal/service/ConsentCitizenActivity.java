package com.samanvay.consent.internal.service;

import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.identity.api.CitizenActivity;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A citizen who has been asked for consent, or has given it, is never folded into another record. */
@Component
class ConsentCitizenActivity implements CitizenActivity {

    private final ConsentRequestRepository requests;
    private final ConsentArtifactRepository artifacts;

    ConsentCitizenActivity(ConsentRequestRepository requests, ConsentArtifactRepository artifacts) {
        this.requests = requests;
        this.artifacts = artifacts;
    }

    @Override
    public boolean hasActivity(UUID citizenId) {
        return requests.existsBySubjectCitizenId(citizenId) || !artifacts.findBySubjectCitizenId(citizenId).isEmpty();
    }
}
