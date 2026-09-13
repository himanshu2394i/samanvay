package com.samanvay.catalog.api;

public record DataSourceDefinition(
        String code,
        String departmentCode,
        String protocol,
        String baseHost,
        String authType,
        String authConfigRef,
        String retryConfig,
        String breakerConfig) {}
