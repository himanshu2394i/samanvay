package com.samanvay.shared.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak-specific checks on top of signature/issuer/expiry: the token was
 * issued for this API ({@code aud}), to one of the realm's own clients
 * ({@code azp}), and is an access token ({@code typ=Bearer}, not an ID or
 * refresh token). Any failure is a 401 - the token is not for us.
 */
final class KeycloakTokenValidator implements OAuth2TokenValidator<Jwt> {

    static final String BEARER = "Bearer";

    private final String audience;
    private final Set<String> allowedClients;

    KeycloakTokenValidator(String audience, List<String> allowedClients) {
        if (allowedClients == null || allowedClients.isEmpty()) {
            throw new IllegalArgumentException("a realm must list its allowed clients (azp)");
        }
        this.audience = audience;
        this.allowedClients = Set.copyOf(allowedClients);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        if (aud == null || !aud.contains(audience)) {
            return invalid("token audience does not include " + audience);
        }
        String azp = jwt.getClaimAsString("azp");
        if (azp == null || azp.isBlank()) {
            return invalid("token has no azp");
        }
        if (!allowedClients.contains(azp)) {
            return invalid("token was issued to a client this API does not accept");
        }
        if (!BEARER.equals(jwt.getClaimAsString("typ"))) {
            return invalid("not an access token (typ must be Bearer)");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
