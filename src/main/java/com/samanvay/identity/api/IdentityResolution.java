package com.samanvay.identity.api;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;

public interface IdentityResolution {
    CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord);

    Page<Candidate> reviewQueue(ReviewFilter filter, Pageable pageable);

    /**
     * Confirms a candidate match as the <em>authenticated reviewer</em>. There is
     * deliberately no reviewerId parameter: the reviewer recorded on the link
     * and in audit is the caller's token subject.
     *
     * @throws ReviewerRequiredException if the caller is not an authenticated reviewer
     */
    Link confirm(UUID candidateId, String note);

    /** Rejects a candidate match as the authenticated reviewer; see {@link #confirm}. */
    void reject(UUID candidateId, String note);
}
