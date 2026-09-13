package com.samanvay.orchestration.api;

import java.time.Instant;
import java.util.UUID;

public record JourneyStarted(
        UUID instanceId, String journeyCode, UUID citizenId, String processInstanceId, Instant slaDueAt) {}
