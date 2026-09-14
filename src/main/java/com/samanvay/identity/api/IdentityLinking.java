package com.samanvay.identity.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityLinking {
    Link assertLink(UUID citizenId, String departmentCode, String localIdType, String localId, AuthProof proof);

    Optional<Link> activeLink(UUID citizenId, String departmentCode);

    List<Link> activeLinks(UUID citizenId);

    void revokeLink(UUID linkId, String reason);

    List<LinkProofProviderInfo> availableProofProviders();
}
