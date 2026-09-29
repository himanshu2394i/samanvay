package com.samanvay.shared.test;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A Postgres integration test whose journeys fetch from the in-process real department fixtures
 * (see {@link RealDepartments}). The {@code demo} profile lets {@code department-service.urls} be set;
 * the dynamic properties override the demo defaults to point the V199 journey sources at the fixtures,
 * and {@link PostgresIntegrationTest}'s dynamic properties still supply the test-token issuers.
 */
@ActiveProfiles("demo")
public abstract class RealDepartmentsIT extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void realDepartments(DynamicPropertyRegistry registry) {
        RealDepartments.register(registry);
    }
}
