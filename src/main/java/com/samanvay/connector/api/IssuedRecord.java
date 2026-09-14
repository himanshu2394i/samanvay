package com.samanvay.connector.api;

import java.util.List;

public record IssuedRecord(
        String stepCode,
        String departmentCode,
        String issuer,
        String liveSystem,
        String liveSystemUrl,
        String title,
        String documentKind,
        List<IssuedField> fields,
        boolean storedInSamanvay,
        String fetchStatus) {}
