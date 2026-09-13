package com.samanvay.identity.api;

import java.util.UUID;

public record LinkRevoked(UUID citizenId, String departmentCode, String reason) {}
