package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.STAFF;
import static com.samanvay.shared.test.KeycloakTestSupport.importUser;
import static com.samanvay.shared.test.KeycloakTestSupport.mailedCode;
import static com.samanvay.shared.test.KeycloakTestSupport.tokenResponse;
import static com.samanvay.shared.test.KeycloakTestSupport.totp;
import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.KeycloakTestSupport;
import com.samanvay.shared.test.KeycloakTestSupport.BrowserLogin;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sign-in paths of the committed realms, driven through Keycloak's real
 * login pages (scripted HTTP, see {@link BrowserLogin}). Passkey sign-in needs
 * a WebAuthn authenticator and is exercised in the browser run instead.
 */
class KeycloakSignInPathsIT {

    @Test
    void staffPasswordAloneNeverFinishesTheLogin() throws Exception {
        String secret = "paths-staff-totp-secret-1";
        importUser(STAFF, "paths-officer", "Paths-officer-pw-1", secret, "\"officer\"", Map.of());

        BrowserLogin login = new BrowserLogin(STAFF, "samanvay-staff-ui")
                .submit(Map.of("username", "paths-officer", "password", "Paths-officer-pw-1"));
        assertThat(login.finished()).isFalse();
        assertThat(login.page().body()).contains("id=\"kc-otp-login-form\"");

        login.submit(Map.of("otp", "000000"));
        assertThat(login.finished()).as("wrong TOTP").isFalse();

        login.submit(Map.of("otp", totp(secret)));
        assertThat(login.finished()).as("password + TOTP").isTrue();
    }

    @Test
    void staffWithoutTotpMustEnrolItBeforeAnyToken() throws Exception {
        importUser(STAFF, "paths-new-officer", "Paths-new-pw-1", null, "\"officer\"", Map.of());
        BrowserLogin login = new BrowserLogin(STAFF, "samanvay-staff-ui")
                .submit(Map.of("username", "paths-new-officer", "password", "Paths-new-pw-1"));
        assertThat(login.finished()).isFalse();
        assertThat(login.follow().finished()).isFalse();
        assertThat(login.page().body()).contains("id=\"kc-totp-settings-form\"");
    }

    @Test
    void staffPasswordGrantWithoutTotpIsRefused() throws Exception {
        importUser(STAFF, "paths-cli-officer", "Paths-cli-pw-1", "paths-cli-totp-secret-01", "\"officer\"", Map.of());
        HttpResponse<String> res = tokenResponse(STAFF, Map.of(
                "grant_type", "password", "client_id", "admin-cli",
                "username", "paths-cli-officer", "password", "Paths-cli-pw-1"));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).doesNotContain("access_token");
    }

    @Test
    void citizenSignsInWithAnEmailedCodeAndNothingElse() throws Exception {
        importUser(CITIZEN, "paths-citizen", null, null, "\"default-roles-samanvay-citizen\"", Map.of());
        String email = "paths-citizen@test.samanvay.invalid";

        Instant sent = Instant.now();
        BrowserLogin login = new BrowserLogin(CITIZEN, "samanvay-citizen-ui");
        assertThat(login.page().body()).doesNotContain("name=\"password\"");
        login.submit(Map.of("username", "paths-citizen"));
        assertThat(login.finished()).isFalse();
        assertThat(login.page().body()).contains("id=\"kc-email-otp-form\"").doesNotContain("name=\"password\"");

        login.submit(Map.of("emailCode", "000000"));
        assertThat(login.finished()).as("wrong code").isFalse();
        assertThat(login.page().body()).contains("That code is not right");

        login.submit(Map.of("emailCode", mailedCode(email, sent)));
        assertThat(login.finished()).as("emailed code").isTrue();
        assertThat(KeycloakTestSupport.claims(login.accessToken()).get("azp").asString()).isEqualTo("samanvay-citizen-ui");
    }

    @Test
    void citizenPasswordGrantIsDeniedEvenIfAPasswordExists() throws Exception {
        // Before this change: a citizen with a password (the old registration form
        // set one) and enrolled TOTP got a token from admin-cli this way.
        String secret = "paths-cit-totp-secret-01";
        importUser(CITIZEN, "paths-pw-citizen", "Paths-cit-pw-1", secret, "\"default-roles-samanvay-citizen\"", Map.of());
        for (String client : new String[] {"admin-cli", "samanvay-citizen-ui"}) {
            HttpResponse<String> res = tokenResponse(CITIZEN, Map.of(
                    "grant_type", "password", "client_id", client,
                    "username", "paths-pw-citizen", "password", "Paths-cit-pw-1", "totp", totp(secret)));
            assertThat(res.statusCode()).as(client + ": " + res.body()).isBetween(400, 401);
            assertThat(res.body()).as(client).doesNotContain("access_token");
        }
    }
}
