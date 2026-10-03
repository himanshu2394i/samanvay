package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * Samanvay has no citizen sign in. With no citizen issuer configured the app still starts, the sign-in config names only the staff
 * realm, and a token from a citizen realm is simply not trusted.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = StaffOnlyRealmIT.NoCitizenRealm.class)
class StaffOnlyRealmIT extends PostgresIntegrationTest {

    /** The shared base sets a citizen issuer as a dynamic property; this initializer puts an empty one in front of it. */
    static class NoCitizenRealm implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("no-citizen-realm", Map.of("samanvay.security.citizen.issuer-uri", "")));
        }
    }

    @LocalServerPort
    int port;

    int status(String token) {
        return TestHttp.as(token).get().uri("http://localhost:" + port + "/api/catalog/departments").exchange((rq, rs) -> rs.getStatusCode().value());
    }

    @Test
    void the_sign_in_config_names_only_the_staff_realm() {
        Map<?, ?> cfg = TestHttp.anonymous().get().uri("http://localhost:" + port + "/ui/auth-config").retrieve().body(Map.class);
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) cfg.get("realms")).keySet())).isEqualTo(java.util.List.of("staff"));
    }

    @Test
    void staff_tokens_work_and_citizen_realm_tokens_are_not_trusted() {
        assertThat(status(TestTokens.officer("o"))).isEqualTo(200);
        assertThat(status(TestTokens.citizen("c"))).isEqualTo(401);
    }
}
