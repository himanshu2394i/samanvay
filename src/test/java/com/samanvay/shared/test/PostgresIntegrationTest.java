package com.samanvay.shared.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {

    // Started once per JVM so Spring's cached context keeps a live JDBC URL.
    // @Container would stop the instance between IT classes and break the cache.
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("samanvay")
            .withUsername("samanvay_migrate")
            .withPassword("samanvay_migrate");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "samanvay_app");
        registry.add("spring.datasource.password", () -> "samanvay_app_dev_password");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appRolePassword", () -> "samanvay_app_dev_password");
    }
}
