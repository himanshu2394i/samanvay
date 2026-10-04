package com.samanvay.ops.internal.service;

/**
 * The one rule for "this document works", shared by the overview and the journey page (docs/contracts/ops-overview.md):
 * the connector is PUBLISHED and its data source is GREEN or AMBER. A source nobody has probed (UNKNOWN) is not known to work, and RED
 * does not. SFTP and JDBC sources cannot be probed, so they are always UNKNOWN: for those, and only those, the last DURABLE trial
 * (the {@code connector_trial} row) decides: working when it SUCCEEDED.
 */
final class SourceWorking {

    private SourceWorking() {}

    static boolean working(boolean published, String sourceHealth, String protocol, String lastTrialOutcome) {
        if (!published || sourceHealth == null) {
            return false;
        }
        return switch (sourceHealth) {
            case "GREEN", "AMBER" -> true;
            case "UNKNOWN" -> ("SFTP_CSV".equals(protocol) || "JDBC".equals(protocol)) && "SUCCESS".equals(lastTrialOutcome);
            default -> false; // RED, or anything else
        };
    }
}
