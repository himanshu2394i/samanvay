package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class DbCredentialsStartupCheckTest {

    private static MockEnvironment env(String app, String migrate, String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        if (app != null) e.setProperty("spring.datasource.password", app);
        if (migrate != null) e.setProperty("spring.flyway.password", migrate);
        return e;
    }

    @Test
    void refusesUnsetPasswords() {
        assertThat(DbCredentialsStartupCheck.problems(env(null, null)))
                .anyMatch(p -> p.contains("SAMANVAY_APP_DB_PASSWORD"))
                .anyMatch(p -> p.contains("SAMANVAY_MIGRATE_DB_PASSWORD"));
        assertThat(DbCredentialsStartupCheck.problems(env(" ", "")))
                .hasSize(2);
    }

    @Test
    void refusesTheRepoDefaults() {
        assertThat(DbCredentialsStartupCheck.problems(env("samanvay_app_dev_password", "real-migrate-pw")))
                .singleElement()
                .asString()
                .contains("SAMANVAY_APP_DB_PASSWORD")
                .doesNotContain("samanvay_app_dev_password");
        assertThat(DbCredentialsStartupCheck.problems(env("real-app-pw", "samanvay_migrate")))
                .singleElement()
                .asString()
                .contains("SAMANVAY_MIGRATE_DB_PASSWORD");
    }

    @Test
    void acceptsProvisionedPasswords() {
        assertThat(DbCredentialsStartupCheck.problems(env("real-app-pw", "real-migrate-pw"))).isEmpty();
    }

    @Test
    void devDemoAndTestProfilesKeepTheirLocalDefaults() {
        for (String profile : new String[] {"dev", "demo", "test"}) {
            assertThat(DbCredentialsStartupCheck.problems(env("samanvay_app_dev_password", "samanvay_migrate", profile)))
                    .as(profile)
                    .isEmpty();
        }
    }

    @Configuration
    static class Empty {}

    /** The hook is registered (META-INF/spring.factories) and stops the boot before any bean, so before Flyway. */
    @Test
    void theRegisteredHookStopsABootWithNoPasswords() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(Empty.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.main.banner-mode=off"))
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("SAMANVAY_APP_DB_PASSWORD");
    }
}
