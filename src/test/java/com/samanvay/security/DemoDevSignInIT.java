package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** The demo profile (like dev) turns the dev sign-in tools on. */
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
