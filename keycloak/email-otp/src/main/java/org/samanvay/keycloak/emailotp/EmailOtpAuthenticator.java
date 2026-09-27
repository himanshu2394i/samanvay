package org.samanvay.keycloak.emailotp;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Sends a one-time code to the user's registered email address and accepts it
 * once. Proves the user holds that mailbox - a factor they already have -
 * before any credential (e.g. a passkey) is enrolled.
 *
 * <p>Only a SHA-256 of the code is kept, in the authentication session (never
 * on the user). The code expires after {@code ttlSeconds}; after
 * {@code maxAttempts} wrong entries it is discarded and a new one must be
 * requested. Wrong codes are reported as {@code INVALID_CREDENTIALS}, so the
 * realm's brute-force protection counts them.
 *
 * <p>Keycloak 26.4 ships no email-OTP or magic-link authenticator, hence this
 * small provider (keycloak/email-otp, built with the app and mounted into the
 * dev/test Keycloak).
 */
public final class EmailOtpAuthenticator implements Authenticator {

    static final String FORM = "samanvay-email-otp.ftl";
    static final String CODE_FIELD = "emailCode";
    static final String NOTE_HASH = "samanvay.email-otp.hash";
    static final String NOTE_EXPIRES = "samanvay.email-otp.expires";
    static final String NOTE_ATTEMPTS = "samanvay.email-otp.attempts";

    static final int DEFAULT_LENGTH = 6;
    static final int DEFAULT_TTL_SECONDS = 300;
    static final int DEFAULT_MAX_ATTEMPTS = 5;

    private static final Logger LOG = Logger.getLogger(EmailOtpAuthenticator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            context.failure(AuthenticationFlowError.INVALID_USER);
            return;
        }
        sendCode(context);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        if (form.containsKey("resend")) {
            sendCode(context);
            return;
        }
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String expected = session.getAuthNote(NOTE_HASH);
        String expires = session.getAuthNote(NOTE_EXPIRES);
        if (expected == null || expires == null || System.currentTimeMillis() > Long.parseLong(expires)) {
            clear(session);
            context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, form(context, "samanvayEmailOtpExpired"));
            return;
        }
        String entered = form.getFirst(CODE_FIELD);
        if (entered != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        sha256(entered.trim()).getBytes(StandardCharsets.US_ASCII))) {
            clear(session);
            context.getUser().setEmailVerified(true);
            context.success();
            return;
        }
        int attempts = Integer.parseInt(session.getAuthNote(NOTE_ATTEMPTS)) + 1;
        if (attempts >= setting(context, "maxAttempts", DEFAULT_MAX_ATTEMPTS)) {
            clear(session);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, form(context, "samanvayEmailOtpTooMany"));
            return;
        }
        session.setAuthNote(NOTE_ATTEMPTS, Integer.toString(attempts));
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, form(context, "samanvayEmailOtpInvalid"));
    }

    private void sendCode(AuthenticationFlowContext context) {
        int length = setting(context, "length", DEFAULT_LENGTH);
        int ttl = setting(context, "ttlSeconds", DEFAULT_TTL_SECONDS);
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        AuthenticationSessionModel session = context.getAuthenticationSession();
        session.setAuthNote(NOTE_HASH, sha256(code.toString()));
        session.setAuthNote(NOTE_EXPIRES, Long.toString(System.currentTimeMillis() + ttl * 1000L));
        session.setAuthNote(NOTE_ATTEMPTS, "0");

        KeycloakSession keycloak = context.getSession();
        RealmModel realm = context.getRealm();
        String realmName = realm.getDisplayName() == null ? realm.getName() : realm.getDisplayName();
        String minutes = Integer.toString(Math.max(1, ttl / 60));
        String text = "Your " + realmName + " sign-in code is " + code + "\n\nIt expires in " + minutes
                + " minutes. If you did not try to sign in, ignore this email.";
        String html = "<p>Your " + escape(realmName) + " sign-in code is</p><p style=\"font-size:24px;letter-spacing:4px\"><b>"
                + code + "</b></p><p>It expires in " + minutes + " minutes. If you did not try to sign in, ignore this email.</p>";
        try {
            keycloak.getProvider(EmailSenderProvider.class)
                    .send(realm.getSmtpConfig(), context.getUser(), "Your sign-in code", text, html);
        } catch (EmailException e) {
            LOG.warnf(e, "could not send the sign-in code email for realm %s", realm.getName());
            clear(session);
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, form(context, "samanvayEmailOtpSendFailed"));
            return;
        }
        context.challenge(form(context, null));
    }

    private static Response form(AuthenticationFlowContext context, String error) {
        var form = context.form().setAttribute("maskedEmail", mask(context.getUser().getEmail()));
        if (error != null) {
            form.setError(error);
        }
        return form.createForm(FORM);
    }

    private static int setting(AuthenticationFlowContext context, String key, int fallback) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        Map<String, String> values = config == null ? null : config.getConfig();
        String v = values == null ? null : values.get(key);
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void clear(AuthenticationSessionModel session) {
        session.removeAuthNote(NOTE_HASH);
        session.removeAuthNote(NOTE_EXPIRES);
        session.removeAuthNote(NOTE_ATTEMPTS);
    }

    static String mask(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
