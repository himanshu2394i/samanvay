package com.samanvay.identity.api;

import com.samanvay.shared.SamanvayException;
import java.util.UUID;

public class CandidateNotFoundException extends SamanvayException {
    public CandidateNotFoundException(UUID id) {
        super("Candidate not found: " + id);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String problemType() {
        return "identity/candidate-not-found";
    }

    @Override
    public String reason() {
        return "CANDIDATE_NOT_FOUND";
    }
}
