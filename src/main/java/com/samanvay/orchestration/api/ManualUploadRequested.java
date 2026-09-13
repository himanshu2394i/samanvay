package com.samanvay.orchestration.api;

import java.util.UUID;

public record ManualUploadRequested(UUID instanceId, String stepCode, String departmentCode) {}
