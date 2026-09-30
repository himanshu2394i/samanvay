package com.samanvay.catalog.api;

/**
 * A data source with its last known connectivity health. {@code healthStatus} is one of
 * GREEN (reachable), RED (unreachable), UNKNOWN (not probed, or a protocol we cannot probe over
 * HTTP such as SFTP/JDBC). {@code detail} is a short human-readable note from the last probe.
 */
public record DataSourceHealth(
        String code,
        String departmentCode,
        String protocol,
        String baseHost,
        String healthStatus,
        String detail) {}
