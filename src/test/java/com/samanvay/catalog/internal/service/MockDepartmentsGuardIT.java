package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Real boot, outside dev/demo/test, against the migrated database (V21-V23 seed PUBLISHED mock.samanvay.test
 * connectors): refuses to start, and starts only with the explicit override.
 */
class MockDepartmentsGuardIT extends PostgresContainerSupport {

    private String[] args(String... extra) {
        String[] common = {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.password=" + APP_PASSWORD,
            "--spring.flyway.url=" + POSTGRES.getJdbcUrl(),
            "--spring.flyway.user=" + POSTGRES.getUsername(),
            "--spring.flyway.password=" + POSTGRES.getPassword(),
            "--spring.flyway.placeholders.appRolePassword=" + APP_PASSWORD,
            "--samanvay.security.staff.issuer-uri=https://idp.example.gov/realms/samanvay-staff",
            "--samanvay.secrets.allow-ephemeral-keys=true",
            "--server.port=0",
        };
        String[] all = new String[common.length + extra.length];
        System.arraycopy(common, 0, all, 0, common.length);
        System.arraycopy(extra, 0, all, common.length, extra.length);
        return all;
    }

    @Test
    void refusesWhilePublishedMockSourcesRemainThenBootsWithTheOverride() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(SamanvayApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run(args("--samanvay.allow-mock-departments=false")))
                .hasStackTraceContaining("Refusing to start")
                .hasStackTraceContaining("revenue-rest-mock");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SamanvayApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args("--samanvay.allow-mock-departments=true"))) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }
}
