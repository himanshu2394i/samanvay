package com.samanvay.consent.api;

import java.util.UUID;

public record ConsentRevoked(UUID consentId, UUID citizenId, int version) {}
