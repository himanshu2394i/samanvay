package com.samanvay.identity.api;

import java.util.UUID;

public record LinkProofContext(UUID citizenId, String departmentCode, String localIdType, String localId) {}
