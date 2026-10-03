package com.samanvay.catalog.api;

/** A department to register. {@code identity} is its login description from the manifest; null when it publishes none. */
public record DepartmentDraft(
        String code, String name, String idpRealm, String contactEmail, Integer defaultSlaMs, DepartmentIdentity identity) {

    /** A department with no department login. */
    public DepartmentDraft(String code, String name, String idpRealm, String contactEmail, Integer defaultSlaMs) {
        this(code, name, idpRealm, contactEmail, defaultSlaMs, null);
    }
}
