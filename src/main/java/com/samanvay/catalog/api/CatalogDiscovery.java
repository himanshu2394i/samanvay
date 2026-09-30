package com.samanvay.catalog.api;

/** Reads a department's published capability manifest so it can be onboarded from just a base URL. */
public interface CatalogDiscovery {

    /**
     * Fetches {@code {baseUrl}/.well-known/samanvay/manifest} and returns it. The host is checked
     * against the same allow-list as a data source (no private/loopback targets). Throws a 400 if
     * the URL is invalid, unreachable, or does not serve a Samanvay manifest.
     */
    DepartmentManifest discover(String baseUrl);
}
