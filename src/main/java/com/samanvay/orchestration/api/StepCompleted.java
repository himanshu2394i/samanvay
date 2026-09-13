package com.samanvay.orchestration.api;

import java.util.UUID;

public record StepCompleted(
        UUID instanceId, String stepCode, String outcome, Long auditRef, String departmentCode, String source) {}
