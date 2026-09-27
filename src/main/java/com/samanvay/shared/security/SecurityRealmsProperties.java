package com.samanvay.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * The two trusted Keycloak realms. Issuer URIs come from configuration/env
 * ({@code SAMANVAY_STAFF_ISSUER_URI}, {@code SAMANVAY_CITIZEN_ISSUER_URI}); a
 * token whose {@code iss} is neither is rejected before any key is fetched.
 *
 * <p>Besides signature, issuer and expiry, every token must carry
 * {@link #audience()} in {@code aud}, {@code typ=Bearer}, and an {@code azp}
 * listed in its realm's {@code allowed-clients} - so a token Keycloak issues to
 * {@code admin-cli}, {@code account-console} or any other client is refused.
 *
 * <p>Key source per realm, in order: {@code public-key-location} (pinned RSA
 * public key, PEM), {@code jwk-set-uri}, otherwise OIDC discovery from the
 * issuer on first use (lazy, so the app boots without the IdP reachable).
 */
@ConfigurationProperties("samanvay.security")
public record SecurityRealmsProperties(String audience, Realm staff, Realm citizen) {

    public static final String DEFAULT_AUDIENCE = "samanvay-api";

    public SecurityRealmsProperties {
        if (audience == null || audience.isBlank()) {
            audience = DEFAULT_AUDIENCE;
        }
    }

    /**
     * @param uiClientId the realm's public browser client, handed to the pages by
     *     {@code /ui/auth-config} so the sign-in script has no hardcoded IdP
     */
    public record Realm(
            String issuerUri, String jwkSetUri, Resource publicKeyLocation, List<String> allowedClients, String uiClientId) {

        public Realm {
            allowedClients = allowedClients == null ? List.of() : List.copyOf(allowedClients);
        }
    }
}
