package com.samanvay.catalog.api;

import java.util.List;
import java.util.Optional;

public interface DepartmentCatalog {
    Optional<Department> byCode(String code);

    List<Department> all();

    Department register(DepartmentDraft draft);

    /** The department's login description (manifest identity block), empty when unknown or it publishes none. */
    default Optional<DepartmentIdentity> identity(String code) {
        return Optional.empty();
    }

    /**
     * Thumbprint (RFC 7638) of the manifest signing key pinned for this department at onboarding, empty when none is pinned.
     * Anything the department later signs for Samanvay (such as a consent statement) must use this key.
     */
    default Optional<String> manifestKeyThumbprint(String code) {
        return Optional.empty();
    }
}
