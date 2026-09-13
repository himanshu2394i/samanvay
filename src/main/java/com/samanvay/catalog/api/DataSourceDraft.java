package com.samanvay.catalog.api;

public record DataSourceDraft(
        String code,
        String departmentCode,
        String protocol,
        String baseHost,
        String authType,
        String authConfigRef) {}
