package com.samanvay.orchestration.api;

import java.time.Instant;
import java.util.UUID;

public record StepPendingSource(
        UUID instanceId, String stepCode, int attemptCount, Instant nextRetryAt, String departmentCode) {}
