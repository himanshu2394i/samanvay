package com.samanvay.audit.api;

import java.time.Instant;

public record AuditQuery(String subjectId, String action, Instant from, Instant to) {}
