package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.STAFF;
import static com.samanvay.shared.test.KeycloakTestSupport.totp;
import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.KeycloakTestSupport;
import com.samanvay.shared.test.KeycloakTestSupport.BrowserLogin;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The DEMO fixed staff code, against a real Keycloak that was started with {@code SAMANVAY_DEMO_FIXED_OTP=000000}, the way a demo
 * server is. The same code in a server WITHOUT the variable is refused (see {@code KeycloakSignInPathsIT}, which types 000000 and
 * expects a refusal, and which also proves enrolment is still forced there).
 *
 * <p>What stays true even with the fixed code on: the password is still required, a wrong code is still refused, and a real
 * authenticator-app code still works.
 */
class StaffFixedOtpIT {

    static final String FIXED = "000000";
    static KeycloakContainer keycloak;
    static String issuer;

    @BeforeAll
    static void start() {
        keycloak = new KeycloakContainer(KeycloakTestSupport.IMAGE)
                .withProviderLibsFrom(List.of(KeycloakTestSupport.EMAIL_OTP_PROVIDER))
                .withRealmImportFiles("/keycloak-realms/samanvay-staff-realm.json")
                .withEnv("SAMANVAY_DEMO_FIXED_OTP", FIXED);
        keycloak.start();
        issuer = keycloak.getAuthServerUrl() + "/realms/" + STAFF;
    }

    @AfterAll
    static void stop() {
        keycloak.stop();
    }

    /** A staff member made through the admin API, with a password and optionally an enrolled authenticator app. */
    static void staff(String username, String password, String totpSecret) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> token = http.send(HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl() + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username="
                        + URLEncoder.encode(keycloak.getAdminUsername(), StandardCharsets.UTF_8) + "&password="
                        + URLEncoder.encode(keycloak.getAdminPassword(), StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        String bearer = JsonMapper.builder().build().readTree(token.body()).get("access_token").asString();
        String creds = "{\"type\":\"password\",\"value\":\"" + password + "\",\"temporary\":false}"
                + (totpSecret == null ? "" : ",{\"type\":\"otp\",\"userLabel\":\"test\",\"secretData\":\"{\\\"value\\\":\\\"" + totpSecret
                        + "\\\"}\",\"credentialData\":\"{\\\"subType\\\":\\\"totp\\\",\\\"digits\\\":6,\\\"counter\\\":0,\\\"period\\\":30,\\\"algorithm\\\":\\\"HmacSHA1\\\"}\"}");
        String user = "{\"username\":\"" + username + "\",\"enabled\":true,\"emailVerified\":true,\"email\":\"" + username
                + "@test.samanvay.invalid\",\"firstName\":\"T\",\"lastName\":\"" + username
                + "\",\"realmRoles\":[\"officer\"],\"requiredActions\":[],\"credentials\":[" + creds + "]}";
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl() + "/admin/realms/" + STAFF + "/partialImport"))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ifResourceExists\":\"OVERWRITE\",\"users\":[" + user + "]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("create " + username + ": " + res.body()).isEqualTo(200);
    }

    BrowserLogin passwordStep(String username, String password) throws Exception {
        return BrowserLogin.at(issuer, "samanvay-staff-ui").submit(java.util.Map.of("username", username, "password", password));
    }

    @Test
    void a_staff_member_with_no_authenticator_app_signs_in_with_the_password_and_the_fixed_code_and_is_not_made_to_enrol() throws Exception {
        staff("fixed-new-officer", "Fixed-new-pw-1", null);
        BrowserLogin login = passwordStep("fixed-new-officer", "Fixed-new-pw-1");
        assertThat(login.finished()).as("the password alone never finishes").isFalse();
        assertThat(login.page().body()).contains("id=\"kc-otp-login-form\"").doesNotContain("kc-totp-settings-form");
        login.submit(java.util.Map.of("otp", FIXED));
        assertThat(login.finished()).as("password + fixed code").isTrue();
    }

    @Test
    void a_wrong_code_is_still_refused_and_the_form_asks_again() throws Exception {
        staff("fixed-wrong-officer", "Fixed-wrong-pw-1", null);
        BrowserLogin login = passwordStep("fixed-wrong-officer", "Fixed-wrong-pw-1");
        login.submit(java.util.Map.of("otp", "111111"));
        assertThat(login.finished()).isFalse();
        assertThat(login.page().body()).contains("id=\"kc-otp-login-form\"");
        login.submit(java.util.Map.of("otp", FIXED));
        assertThat(login.finished()).as("then the fixed code works").isTrue();
    }

    @Test
    void the_fixed_code_never_replaces_the_password() throws Exception {
        staff("fixed-pw-officer", "Fixed-pw-pw-1", null);
        BrowserLogin login = passwordStep("fixed-pw-officer", "not-the-password");
        assertThat(login.finished()).isFalse();
        assertThat(login.page().body()).doesNotContain("kc-otp-login-form");
        login.submit(java.util.Map.of("otp", FIXED)); // no OTP field on this page: still on the username/password form
        assertThat(login.finished()).isFalse();
    }

    @Test
    void a_staff_member_with_an_authenticator_app_can_use_the_real_code_or_the_fixed_one() throws Exception {
        String secret = "fixed-it-totp-secret-01";
        staff("fixed-app-officer", "Fixed-app-pw-1", secret);
        BrowserLogin real = passwordStep("fixed-app-officer", "Fixed-app-pw-1").submit(java.util.Map.of("otp", totp(secret)));
        assertThat(real.finished()).as("real authenticator code").isTrue();
        BrowserLogin fixed = passwordStep("fixed-app-officer", "Fixed-app-pw-1").submit(java.util.Map.of("otp", FIXED));
        assertThat(fixed.finished()).as("fixed code").isTrue();
    }
}
