package com.samanvay.identity.api;

import java.util.UUID;

public record Link(
        UUID id,
        UUID citizenId,
        String departmentCode,
        String localIdType,
        String localIdToken,
        String provenance,
        String status) {}
