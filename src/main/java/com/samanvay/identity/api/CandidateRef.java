package com.samanvay.identity.api;

import java.util.UUID;

public record CandidateRef(String kind, UUID citizenId) {
    public static CandidateRef alreadyLinked(UUID citizenId) {
        return new CandidateRef("ALREADY_LINKED", citizenId);
    }

    public static CandidateRef queued() {
        return new CandidateRef("QUEUED", null);
    }
}
