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
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoEndpointsGatedIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext ctx;

    /**
     * Without the demo profile the routes do not exist: even an admin token
     * (which the security rules would let through) gets 404. Anonymous callers
     * are stopped earlier with 401, like every other /api route.
     */
    @Test
    void tamperAndChaosAreAbsentWithoutDemoProfile() {
        assertThat(ctx.getBeansOfType(DemoAuditTamper.class)).isEmpty();
        RestClient admin = TestHttp.as(TestTokens.admin("admin-gate"));
        assertThat(status(admin, "/api/audit/demo/tamper/1").value()).isEqualTo(404);
        assertThat(status(admin, "/api/connector/chaos/revenue-rest-mock/kill").value()).isEqualTo(404);
        assertThat(getStatus(admin, "/api/connector/chaos/revenue-rest-mock").value()).isEqualTo(404);

        RestClient anonymous = TestHttp.anonymous();
        assertThat(status(anonymous, "/api/audit/demo/tamper/1").value()).isEqualTo(401);
        assertThat(getStatus(anonymous, "/api/connector/chaos/revenue-rest-mock").value()).isEqualTo(401);
    }

    private HttpStatusCode status(RestClient http, String path) {
        return http.post()
                .uri("http://localhost:" + port + path)
                .exchange((req, res) -> res.getStatusCode());
    }

    private HttpStatusCode getStatus(RestClient http, String path) {
        return http.get()
                .uri("http://localhost:" + port + path)
                .exchange((req, res) -> res.getStatusCode());
    }
}
