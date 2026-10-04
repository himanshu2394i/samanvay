package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.internal.service.DemoAuditTamper;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The public demo runs under the demo profile, but audit tamper and chaos kill/revive must still be absent
 * unless samanvay.demo.tamper-endpoints / samanvay.demo.chaos-endpoints are explicitly set to true.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoSwitchesOffByDefaultIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext ctx;

    @Test
    void demoProfileAloneDoesNotExposeTamperOrChaos() {
        assertThat(ctx.getBeansOfType(DemoAuditTamper.class)).isEmpty();
        RestClient admin = TestHttp.as(TestTokens.admin("admin-switch"));
        int tamper = post(admin, "/api/audit/demo/tamper/1");
        assertThat(tamper).isEqualTo(404);
        int kill = post(admin, "/api/connector/chaos/revenue-rest-mock/kill");
        assertThat(kill).isEqualTo(404);
        int chaosGet = admin.get()
                .uri("http://localhost:" + port + "/api/connector/chaos/revenue-rest-mock")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(chaosGet).isEqualTo(404);
    }

    private int post(RestClient http, String path) {
        return http.post().uri("http://localhost:" + port + path).exchange((req, res) -> res.getStatusCode().value());
    }
}
