package com.samanvay.tracking.api;

import java.util.UUID;

public record ApplicationSummary(String referenceNo, UUID citizenId, String journeyCode, String status) {}
