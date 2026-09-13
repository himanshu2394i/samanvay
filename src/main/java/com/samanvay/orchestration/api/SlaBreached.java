package com.samanvay.orchestration.api;

import java.time.Instant;
import java.util.UUID;

public record SlaBreached(UUID instanceId, String stepCode, Instant dueAt) {}
