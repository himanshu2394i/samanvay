package com.samanvay.catalog.api;

/**
 * A fetched manifest and who signed it. {@code keyThumbprint} is the RFC 7638 thumbprint of the key whose signature on these exact
 * bytes was verified, or null when the department did not sign. A signature that is present but invalid is never returned: the
 * fetch fails instead.
 */
public record DiscoveredManifest(DepartmentManifest manifest, String keyThumbprint) {

    public boolean signed() {
        return keyThumbprint != null;
    }
}
