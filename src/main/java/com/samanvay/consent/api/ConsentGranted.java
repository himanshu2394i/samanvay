package com.samanvay.consent.api;

import java.util.List;
import java.util.UUID;

public record ConsentGranted(UUID consentId, UUID citizenId, String requesterId, List<String> categories) {}
