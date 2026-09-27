package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RealmIssuerStartupCheckTest {

    @Configuration
    @EnableConfigurationProperties(SecurityRealmsProperties.class)
    @Import(RealmIssuerStartupCheck.class)
    static class Checked {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Checked.class);

    @Test
    void refusesToBootWithoutIssuers() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("samanvay.security.staff.issuer-uri is not set")
                    .hasMessageContaining("samanvay.security.citizen.issuer-uri is not set");
        });
    }

    @Test
    void refusesHttpIssuersOutsideDevAndDemo() {
        runner.withPropertyValues(
                        "samanvay.security.staff.issuer-uri=http://localhost:8180/realms/samanvay-staff",
                        "samanvay.security.citizen.issuer-uri=https://idp.example.gov/realms/samanvay-citizen")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("staff.issuer-uri must be an https URL")
                            .hasMessageNotContaining("citizen.issuer-uri");
                });
    }

    @Test
    void bootsWithHttpsIssuers() {
        runner.withPropertyValues(
                        "samanvay.security.staff.issuer-uri=https://idp.example.gov/realms/samanvay-staff",
                        "samanvay.security.citizen.issuer-uri=https://idp.example.gov/realms/samanvay-citizen")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void devAndDemoMayUseTheLocalHttpKeycloakButStillNeedIssuers() {
        for (String profile : new String[] {"dev", "demo"}) {
            runner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "samanvay.security.staff.issuer-uri=http://localhost:8180/realms/samanvay-staff",
                            "samanvay.security.citizen.issuer-uri=http://localhost:8180/realms/samanvay-citizen")
                    .run(ctx -> assertThat(ctx).hasNotFailed());
            runner.withPropertyValues("spring.profiles.active=" + profile).run(ctx -> assertThat(ctx).hasFailed());
        }
    }
}
