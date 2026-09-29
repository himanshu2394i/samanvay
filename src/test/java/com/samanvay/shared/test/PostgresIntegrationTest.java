package com.samanvay.shared.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Shared Postgres plus real JWT validation against the per-JVM {@link TestTokens} realms. */
public abstract class PostgresIntegrationTest extends PostgresContainerSupport {

    @DynamicPropertySource
    static void testTokenRealms(DynamicPropertyRegistry registry) {
        // Real JWT validation against per-JVM test keys (see TestTokens); no Keycloak needed.
        registry.add("samanvay.security.staff.issuer-uri", () -> TestTokens.STAFF_ISSUER);
        registry.add("samanvay.security.staff.public-key-location", () -> TestTokens.STAFF_PUBLIC_KEY_PEM.toUri().toString());
        registry.add("samanvay.security.citizen.issuer-uri", () -> TestTokens.CITIZEN_ISSUER);
        registry.add(
                "samanvay.security.citizen.public-key-location", () -> TestTokens.CITIZEN_PUBLIC_KEY_PEM.toUri().toString());
        // The real clients plus the department client ids tests use to tell departments apart.
        registry.add("samanvay.security.staff.allowed-clients", () -> String.join(",", TestTokens.STAFF_CLIENTS));
        // These contexts run outside dev/demo with the in-process EnvSecretStore's ephemeral keys; the production
        // secrets boot guard honours this only because JUnit is on the classpath (see SecretStoreStartupCheck).
        registry.add("samanvay.secrets.allow-ephemeral-keys", () -> "true");
    }
}
