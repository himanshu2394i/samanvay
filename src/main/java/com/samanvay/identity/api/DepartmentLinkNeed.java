package com.samanvay.identity.api;

import java.util.List;

/**
 * One department a journey needs documents from, and whether this citizen is linked to it. {@code departmentLoginAvailable}
 * says the department publishes its own login, so the citizen can link by logging in there.
 */
public record DepartmentLinkNeed(
        String departmentCode,
        String departmentName,
        List<String> categories,
        boolean linked,
        String localIdType,
        String localIdToken,
        boolean departmentLoginAvailable) {}
