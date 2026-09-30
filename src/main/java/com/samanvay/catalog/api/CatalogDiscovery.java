package com.samanvay.catalog.api;

import java.util.List;

/** Reads a department's published capability manifest, and probes/monitors data source health. */
public interface CatalogDiscovery {

    /**
     * Fetches {@code {baseUrl}/.well-known/samanvay/manifest} and returns it. The host is checked
     * against the same allow-list as a data source (no private/loopback targets). Throws a 400 if
     * the URL is invalid, unreachable, or does not serve a Samanvay manifest.
     */
    DepartmentManifest discover(String baseUrl);

    /** Every registered data source with its last known connectivity health. */
    List<DataSourceHealth> listDataSources();

    /** Live connectivity check: reaches the source over the network and records GREEN/RED/UNKNOWN. */
    DataSourceHealth probe(String dataSourceCode);
}
