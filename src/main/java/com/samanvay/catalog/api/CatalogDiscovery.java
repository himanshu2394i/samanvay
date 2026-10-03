package com.samanvay.catalog.api;

import java.util.List;

/** Reads a department's published capability manifest, and probes/monitors data source health. */
public interface CatalogDiscovery {

    /**
     * Fetches {@code {baseUrl}/.well-known/samanvay/manifest} and returns it. The host is checked
     * against the same allow-list as a data source (no private/loopback targets). Throws a 400 if
     * the URL is invalid, unreachable, or does not serve a Samanvay manifest.
     */
    default DepartmentManifest discover(String baseUrl) {
        return fetch(baseUrl).manifest();
    }

    /**
     * Like {@link #discover} but also says which key signed the manifest. Sends the department's discovery credential when one
     * was provisioned for its host (SecretStore key {@code manifest-<host>-credential}, header {@code X-Discovery-Key}). A
     * signature that is present but invalid fails the call; an unsigned manifest comes back with a null thumbprint.
     */
    DiscoveredManifest fetch(String baseUrl);

    /** Every registered data source with its last known connectivity health. */
    List<DataSourceHealth> listDataSources();

    /** Live connectivity check: reaches the source over the network and records GREEN/RED/UNKNOWN. */
    DataSourceHealth probe(String dataSourceCode);
}
