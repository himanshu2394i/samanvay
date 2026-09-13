package com.samanvay.orchestration.api;

import java.util.UUID;

public record JourneyInstance(UUID id, String processInstanceId, String journeyCode, UUID citizenId) {}
