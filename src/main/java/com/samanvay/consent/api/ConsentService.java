package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentService {
    ConsentRequest request(ConsentRequestDraft draft);

    /** Grants as the citizen themself; {@code by} is the token principal recorded in the audit entry. */
    ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof, PrincipalRef by);

    default ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof) {
        return grant(requestId, citizenId, proof, citizenSelf(citizenId));
    }

    /**
     * Withdraws the citizen's own consent. A consent of another citizen is "not found".
     * {@code by} is the token principal, recorded as {@code revoked_by} and in the audit entry.
     * Revoking a consent that is no longer active changes nothing.
     */
    void revoke(UUID consentId, UUID citizenId, String reason, PrincipalRef by);

    default void revoke(UUID consentId, UUID citizenId, String reason) {
        revoke(consentId, citizenId, reason, citizenSelf(citizenId));
    }

    /** The citizen whose consent this is, or empty if there is no such consent. */
    Optional<UUID> ownerOf(UUID consentId);

    List<ConsentArtifact> forCitizen(UUID citizenId);

    Optional<ConsentArtifact> find(RequesterRef requester, SubjectRef subject, DataCategory category, PurposeCode purpose);

    private static PrincipalRef citizenSelf(UUID citizenId) {
        return new PrincipalRef(PrincipalRef.Kind.CITIZEN, citizenId.toString());
    }
}
