package com.samanvay.orchestration.api;

import java.util.UUID;

public record ApplicationStateChanged(UUID instanceId, String newStatus) {}
