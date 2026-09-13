package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentService {
    ConsentRequest request(ConsentRequestDraft draft);

    ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof);

    void revoke(UUID consentId, UUID citizenId, String reason);

    List<ConsentArtifact> forCitizen(UUID citizenId);

    Optional<ConsentArtifact> find(RequesterRef requester, SubjectRef subject, DataCategory category, PurposeCode purpose);
}
