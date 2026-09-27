package com.samanvay.identity.api;

import java.util.UUID;

public interface CitizenProfiles {
    /** Assisted registration (staff on behalf of a citizen); not bound to any sign-in. */
    UUID register(ProfileDraft draft);

    /**
     * Self-registration by a signed-in citizen: binds the new record to the
     * token subject. Idempotent - a subject already bound gets its existing id.
     */
    UUID registerSelf(ProfileDraft draft, String authSubject);

    Profile profile(UUID citizenId);
}
