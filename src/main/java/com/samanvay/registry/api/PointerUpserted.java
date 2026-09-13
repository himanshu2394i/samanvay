package com.samanvay.registry.api;

import java.util.UUID;

public record PointerUpserted(UUID pointerId, UUID subjectId, String departmentCode, String dataCategory) {}
