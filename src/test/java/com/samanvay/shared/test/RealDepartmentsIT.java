package com.samanvay.shared.test;

import org.springframework.test.context.ActiveProfiles;

/**
 * A {@link PostgresIntegrationTest} that also runs under the {@code demo} profile, for ITs that need
 * demo-only surfaces (department chaos, audit tamper). The real department fixtures are already wired
 * by {@link PostgresIntegrationTest} for every Postgres IT; this only adds the profile.
 */
@ActiveProfiles("demo")
public abstract class RealDepartmentsIT extends PostgresIntegrationTest {}
