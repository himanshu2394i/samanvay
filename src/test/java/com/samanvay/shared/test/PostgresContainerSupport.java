package com.samanvay.shared.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The shared Postgres container and the datasource/Flyway wiring, without any
 * token-realm settings. Most ITs extend {@link PostgresIntegrationTest}, which
 * adds the {@link TestTokens} realms; ITs against a real Keycloak extend this
 * directly and register that Keycloak's issuers instead.
 */
public abstract class PostgresContainerSupport {

    // Deliberately not the repo defaults (samanvay_app_dev_password / samanvay_migrate): ITs run outside the dev/demo/test
    // profiles, where DbCredentialsStartupCheck refuses the defaults.
    public static final String APP_PASSWORD = "it_app_pw_9f3a";
    private static final String MIGRATE_PASSWORD = "it_migrate_pw_4c1d";

    // Started once per JVM so Spring's cached context keeps a live JDBC URL.
    // @Container would stop the instance between IT classes and break the cache.
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("samanvay")
            .withUsername("samanvay_migrate")
            .withPassword(MIGRATE_PASSWORD)
            // Every cached Spring context (one per distinct test configuration, e.g. one per Keycloak IT) keeps its own
            // connection pool; the default limit of 100 runs out once the whole suite has run.
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "samanvay_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appRolePassword", () -> APP_PASSWORD);
        // V21-V23 seed PUBLISHED mock department sources; the test runtime uses them (see MockDepartmentsStartupCheck).
        registry.add("samanvay.allow-mock-departments", () -> "true");
    }
}
