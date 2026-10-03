package com.samanvay.catalog.api;

/**
 * A data source to register. {@code authSpecJson} is the non-secret auth description from the department's manifest
 * (scheme and parameter names); secret values are never part of a draft (they live in the SecretStore).
 */
public record DataSourceDraft(
        String code,
        String departmentCode,
        String protocol,
        String baseHost,
        String authType,
        String authConfigRef,
        String authSpecJson) {

    /** A data source with no declared auth spec: the auth type alone decides. */
    public DataSourceDraft(
            String code, String departmentCode, String protocol, String baseHost, String authType, String authConfigRef) {
        this(code, departmentCode, protocol, baseHost, authType, authConfigRef, null);
    }
}
