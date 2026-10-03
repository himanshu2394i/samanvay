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

/**
 * There is no one-click demo login any more, not even in the demo profile: people sign in through Keycloak (citizens
 * sign up with name, email and password; officers and admins use the ready-made staff accounts). So the demo profile
 * has no demo-signin endpoint, no paste-token script, no "devSignIn" switch, and trusts no issuer but the two realms.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class NoDemoSignInIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void authConfigOnlyNamesTheTwoRealms() {
        Map<?, ?> cfg = TestHttp.anonymous().get().uri(url("/ui/auth-config")).retrieve().body(Map.class);
        assertThat(cfg.containsKey("devSignIn")).isFalse();
        assertThat(new java.util.HashSet<Object>(((Map<?, ?>) cfg.get("realms")).keySet())).isEqualTo(java.util.Set.of("staff", "citizen"));
    }

    @Test
    void theDemoSignInEndpointAndThePasteTokenScriptAreGone() {
        int code = TestHttp.anonymous().post().uri(url("/ui/demo-signin"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("role", "admin"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(code).isEqualTo(404);
        int script = TestHttp.anonymous().get().uri(url("/shared/auth-dev.js")).exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(script).isEqualTo(404);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
