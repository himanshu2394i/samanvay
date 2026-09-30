package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** The demo profile (like dev) turns the dev sign-in tools on, including the one-click demo login. */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoDevSignInIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void devSignInIsOnAndItsScriptIsServed() {
        Map<?, ?> cfg = TestHttp.anonymous().get().uri(url("/ui/auth-config")).retrieve().body(Map.class);
        assertThat(cfg.get("devSignIn")).isEqualTo(true);
        String script = TestHttp.anonymous().get().uri(url("/shared/auth-dev.js")).retrieve().body(String.class);
        assertThat(script).contains("Paste token").contains("SamanvayAuthDev");
    }

    @Test
    void demoSignInMintsRoleTokensTheApiAcceptsAndScopesByRole() {
        // an admin token is accepted and can read the staff-only connectors list
        String adminToken = mint("admin");
        // the token carries a jti (session proof) — consent grants require one (ConsentController)
        String payload = new String(
                java.util.Base64.getUrlDecoder().decode(adminToken.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("\"jti\"");
        RestClient asAdmin = as(adminToken);
        assertThat(status(asAdmin, "/api/catalog/connectors")).isEqualTo(200);

        // a citizen token is accepted (journeys read) but not for the staff-only connectors list
        RestClient asCitizen = as(mint("citizen"));
        assertThat(status(asCitizen, "/api/catalog/journeys")).isEqualTo(200);
        assertThat(status(asCitizen, "/api/catalog/connectors")).isEqualTo(403);
    }

    private String mint(String role) {
        Map<?, ?> body = TestHttp.anonymous().post().uri(url("/ui/demo-signin"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", role)).retrieve().body(Map.class);
        return (String) body.get("access_token");
    }

    private static RestClient as(String token) {
        return RestClient.builder().defaultHeader("Authorization", "Bearer " + token).build();
    }

    private int status(RestClient client, String path) {
        return client.get().uri(url(path)).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
