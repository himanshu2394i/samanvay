package com.samanvay.shared;

import java.util.Objects;

/**
 * Who triggered an access, taken from the authenticated token - never from a
 * request body or header. Carried on {@code consent.api.AccessGrant} (signed)
 * and copied by {@code connector} into {@code DATA_ACCESSED}/{@code GRANT_REJECTED}
 * audit entries, so a citizen's "who accessed my data" view names a person or
 * a department client rather than "connector".
 *
 * <p>Lives in {@code shared} because consent, connector and orchestration need
 * the identical type (LLD §6 rule).
 */
public record PrincipalRef(Kind kind, String id) {

    public enum Kind {
        /** Citizen acting on their own record (self-service portal). */
        CITIZEN,
        /** Staff realm: department officer. */
        OFFICER,
        /** Staff realm: identity reviewer. */
        REVIEWER,
        /** Staff realm: integration admin. */
        ADMIN,
        /** Client-credentials token of a department integration; {@code id} is the client id. */
        DEPARTMENT
    }

    public PrincipalRef {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("principal id must not be blank");
        }
    }
}
