package com.samanvay.identity.api;

import java.util.List;

public record DepartmentLinkNeed(
        String departmentCode,
        String departmentName,
        List<String> categories,
        boolean linked,
        String localIdType,
        String localIdToken) {}
