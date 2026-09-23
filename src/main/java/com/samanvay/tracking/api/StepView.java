package com.samanvay.tracking.api;

import java.time.Instant;
import java.util.Optional;

public record StepView(
        String stepCode,
        String departmentCode,
        String status,
        Instant startedAt,
        Instant completedAt,
        Instant slaDueAt,
        String source,
        Optional<String> outcome,
        Optional<Instant> dataAsOf) {}
