package com.samanvay.connector.api;

import java.util.Map;

/**
 * One call to a department source.
 *
 * <p>{@code authType} and {@code authSpecJson} are the non-secret half of how to authenticate (from the data source /
 * the department manifest's {@code auth} block); the secret values are looked up by the adapter from the SecretStore,
 * never carried here. {@code access} is protocol-specific, non-secret detail from the connector capability (for
 * example {@code key_column} for SFTP/JDBC, {@code view} for JDBC); it is empty when the connector declares none.
 */
public record AdapterRequest(
        String dataSourceCode,
        String protocol,
        String host,
        String endpoint,
        String template,
        Map<String, String> boundInputs,
        String authConfigRef,
        String authType,
        String authSpecJson,
        Map<String, String> access) {

    public AdapterRequest {
        access = access == null ? Map.of() : Map.copyOf(access);
    }

    /** A request with a declared auth scheme but no protocol-specific access details. */
    public AdapterRequest(
            String dataSourceCode,
            String protocol,
            String host,
            String endpoint,
            String template,
            Map<String, String> boundInputs,
            String authConfigRef,
            String authType,
            String authSpecJson) {
        this(dataSourceCode, protocol, host, endpoint, template, boundInputs, authConfigRef, authType, authSpecJson, Map.of());
    }

    /** A request with no declared auth scheme (the original shape): nothing is applied beyond the transport's own login. */
    public AdapterRequest(
            String dataSourceCode,
            String protocol,
            String host,
            String endpoint,
            String template,
            Map<String, String> boundInputs,
            String authConfigRef) {
        this(dataSourceCode, protocol, host, endpoint, template, boundInputs, authConfigRef, null, null, Map.of());
    }
}
