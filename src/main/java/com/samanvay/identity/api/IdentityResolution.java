package com.samanvay.identity.api;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;

public interface IdentityResolution {
    CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord);

    Page<Candidate> reviewQueue(ReviewFilter filter, Pageable pageable);

    Link confirm(UUID candidateId, String reviewerId, String note);

    void reject(UUID candidateId, String reviewerId, String note);
}
