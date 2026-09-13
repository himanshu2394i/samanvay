package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.internal.service.DemoAuditTamper;
import com.samanvay.shared.test.PostgresIntegrationTest;
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

    @Test
    void tamperAndChaosAreAbsentWithoutDemoProfile() {
        assertThat(ctx.getBeansOfType(DemoAuditTamper.class)).isEmpty();
        RestClient http = RestClient.create();
        assertThat(status(http, "/api/audit/demo/tamper/1").value()).isEqualTo(404);
        assertThat(status(http, "/api/connector/chaos/revenue-rest-mock/kill").value()).isEqualTo(404);
    }

    private HttpStatusCode status(RestClient http, String path) {
        return http.post()
                .uri("http://localhost:" + port + path)
                .exchange((req, res) -> res.getStatusCode());
    }
}
