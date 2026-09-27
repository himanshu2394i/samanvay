package com.samanvay.shared.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** The two local profiles that may use plain-http issuers and the dev sign-in tools. */
public final class DevProfiles {

    public static final Profiles DEV_OR_DEMO = Profiles.of("dev", "demo");

    private DevProfiles() {}

    public static boolean active(Environment env) {
        return env.acceptsProfiles(DEV_OR_DEMO);
    }
}
