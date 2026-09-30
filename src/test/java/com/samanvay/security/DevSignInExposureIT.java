package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Default boot (neither dev nor demo): the server hands out https issuers and
 * devSignIn=false, does not serve the dev sign-in script, and no page or
 * script it serves mentions the local Keycloak or the paste-token bar.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevSignInExposureIT extends PostgresIntegrationTest {

    static final List<String> DEV_ONLY_MARKERS = List.of("localhost:8180", "Paste token", "Dev sign-in");

    @LocalServerPort
    int port;

    @Test
    void authConfigHasNoDevSignInAndOnlyHttpsIssuers() {
        Map<?, ?> cfg = TestHttp.anonymous().get().uri(url("/ui/auth-config")).retrieve().body(Map.class);
        assertThat(cfg.get("devSignIn")).isEqualTo(false);
        Map<?, ?> realms = (Map<?, ?>) cfg.get("realms");
        for (String realm : List.of("staff", "citizen")) {
            Map<?, ?> r = (Map<?, ?>) realms.get(realm);
            assertThat(r.get("issuer").toString()).as(realm).startsWith("https://");
            assertThat(r.get("clientId").toString()).as(realm).isEqualTo("samanvay-" + realm + "-ui");
        }
    }

    @Test
    void devSignInScriptIsNotServed() {
        assertThat(status("/shared/auth-dev.js")).isEqualTo(404);
        assertThat(status("/dev-static/shared/auth-dev.js")).isEqualTo(404);
    }

    @Test
    void demoSignInEndpointIsNotServed() {
        int code = TestHttp.anonymous().post().uri(url("/ui/demo-signin"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("role", "admin"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(code).isEqualTo(404);
    }

    @Test
    void noServedPageOrScriptMentionsTheLocalKeycloakOrTheDevBar() throws IOException {
        Path root = Path.of("src/main/resources/static");
        List<String> hits = new ArrayList<>();
        int served = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!(name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css"))) {
                    continue;
                }
                String path = "/" + root.relativize(file).toString().replace('\\', '/');
                String body = TestHttp.anonymous().get().uri(url(path)).retrieve().body(String.class);
                served++;
                for (String marker : DEV_ONLY_MARKERS) {
                    if (body != null && body.contains(marker)) {
                        hits.add(path + " contains " + marker);
                    }
                }
            }
        }
        assertThat(served).isGreaterThan(20);
        assertThat(hits).isEmpty();
    }

    private int status(String path) {
        return TestHttp.anonymous().get().uri(url(path)).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
