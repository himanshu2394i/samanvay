package com.samanvay.catalog.api;

public record DataSourceDefinition(
        String code,
        String departmentCode,
        String protocol,
        String baseHost,
        String authType,
        String authConfigRef,
        String retryConfig,
        String breakerConfig,
        String authSpecJson) {

    /** A definition with no auth spec (the original shape). */
    public DataSourceDefinition(
            String code,
            String departmentCode,
            String protocol,
            String baseHost,
            String authType,
            String authConfigRef,
            String retryConfig,
            String breakerConfig) {
        this(code, departmentCode, protocol, baseHost, authType, authConfigRef, retryConfig, breakerConfig, "{}");
    }
}
