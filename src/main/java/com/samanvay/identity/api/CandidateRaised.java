package com.samanvay.identity.api;

import java.util.UUID;

public record CandidateRaised(UUID candidateId, UUID citizenId, String departmentCode, double score) {}
