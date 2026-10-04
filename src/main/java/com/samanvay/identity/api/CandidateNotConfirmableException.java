package com.samanvay.identity.api;

import com.samanvay.shared.SamanvayException;
import java.util.UUID;

/** The candidate was already decided, or its citizen has been merged into another record, so an officer cannot act on it. */
public class CandidateNotConfirmableException extends SamanvayException {
    public CandidateNotConfirmableException(UUID id) {
        super("Candidate can no longer be decided: " + id);
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "identity/candidate-not-confirmable";
    }

    @Override
    public String reason() {
        return "CANDIDATE_NOT_CONFIRMABLE";
    }
}
