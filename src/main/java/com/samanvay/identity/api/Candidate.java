package com.samanvay.identity.api;

import java.util.UUID;

public record Candidate(UUID id, UUID citizenId, String departmentCode, double score, String status) {}
