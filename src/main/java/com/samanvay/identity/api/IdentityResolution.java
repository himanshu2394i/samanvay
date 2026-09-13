package com.samanvay.identity.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface IdentityResolution {
    CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord);

    Link confirm(UUID candidateId, String reviewerId, String note);

    void reject(UUID candidateId, String reviewerId, String note);
}
