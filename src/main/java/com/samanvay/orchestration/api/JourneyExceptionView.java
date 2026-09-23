package com.samanvay.orchestration.api;

import java.time.Instant;
import java.util.UUID;

public record JourneyExceptionView(UUID id, UUID instanceId, String stepCode, String reason, Instant createdAt) {}
