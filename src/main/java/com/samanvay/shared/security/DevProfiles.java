package com.samanvay.shared.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** The two local profiles that may use plain-http issuers and the dev sign-in tools. */
public final class DevProfiles {

    public static final Profiles DEV_OR_DEMO = Profiles.of("dev", "demo");

    /** Plus the test profile, for guards that only protect deployments (DB passwords, mock departments). */
    public static final Profiles DEV_DEMO_OR_TEST = Profiles.of("dev", "demo", "test");

    private DevProfiles() {}

    public static boolean active(Environment env) {
        return env.acceptsProfiles(DEV_OR_DEMO);
    }

    public static boolean activeOrTest(Environment env) {
        return env.acceptsProfiles(DEV_DEMO_OR_TEST);
    }
}
