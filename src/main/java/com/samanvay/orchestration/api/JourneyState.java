package com.samanvay.orchestration.api;

import java.util.Map;
import java.util.UUID;

public record JourneyState(UUID id, String status, Map<String, String> stepOutcomes) {}
