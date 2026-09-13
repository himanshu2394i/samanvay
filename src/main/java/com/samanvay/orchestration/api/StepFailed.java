package com.samanvay.orchestration.api;

import java.util.UUID;

public record StepFailed(UUID instanceId, String stepCode, String outcome, String departmentCode) {}
