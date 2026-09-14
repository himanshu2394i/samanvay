package com.samanvay.connector.api;

public record LockerDocument(
        String id,
        String title,
        String issuer,
        String liveSystem,
        String liveSystemUrl,
        String note) {}
