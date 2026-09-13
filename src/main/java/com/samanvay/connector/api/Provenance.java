package com.samanvay.connector.api;

import java.time.Instant;
import java.util.UUID;

public record Provenance(String departmentCode, Instant asOf, String connectorRef, UUID grantId, String freshnessMode) {}
