package org.samanvay.keycloak.fixedotp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * The DEMO-ONLY fixed second-factor code. It exists only when the operator sets the environment variable
 * {@value #ENV} to exactly six digits; anything else (unset, empty, a typo, an unresolved placeholder) means it is off and
 * the normal authenticator-app code is the only thing that works.
 */
final class FixedOtp {

    static final String ENV = "SAMANVAY_DEMO_FIXED_OTP";
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");

    private FixedOtp() {}

    /** The configured fixed code, or null when off. */
    static String resolve(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return SIX_DIGITS.matcher(v).matches() ? v : null;
    }

    static String fromEnvironment() {
        return resolve(System.getenv(ENV));
    }

    static boolean matches(String fixed, String submitted) {
        if (fixed == null || submitted == null) {
            return false;
        }
        return MessageDigest.isEqual(fixed.getBytes(StandardCharsets.UTF_8), submitted.trim().getBytes(StandardCharsets.UTF_8));
    }
}
