package com.samanvay.registry.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.time.LocalDate;

public record PointerUpsert(
        SubjectRef subject,
        String subjectType,
        String departmentCode,
        DataCategory category,
        String sourceRef,
        LocalDate issuedAt,
        LocalDate validUntil,
        Instant asOf,
        FreshnessMode freshnessMode) {}
