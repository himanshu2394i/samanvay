package com.samanvay.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * The two trusted Keycloak realms. Issuer URIs come from configuration/env
 * ({@code SAMANVAY_STAFF_ISSUER_URI}, {@code SAMANVAY_CITIZEN_ISSUER_URI}); a
 * token whose {@code iss} is neither is rejected before any key is fetched.
 *
 * <p>Key source per realm, in order: {@code public-key-location} (pinned RSA
 * public key, PEM), {@code jwk-set-uri}, otherwise OIDC discovery from the
 * issuer on first use (lazy, so the app boots without the IdP reachable).
 */
@ConfigurationProperties("samanvay.security")
public record SecurityRealmsProperties(Realm staff, Realm citizen) {

    public record Realm(String issuerUri, String jwkSetUri, Resource publicKeyLocation) {}
}
