package com.samanvay.identity.api;

import java.util.UUID;

public interface CitizenProfiles {
    UUID register(ProfileDraft draft);

    Profile profile(UUID citizenId);
}
