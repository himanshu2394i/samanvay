package com.samanvay.shared.security;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * {@link CitizenTokenVerifier} over the same decoder construction the resource server uses
 * for the citizen realm ({@link SecurityConfig#decoder}), so the audience / azp / typ checks
 * cannot drift. The decoder is built on first use, so the app still boots without Keycloak.
 */
@Component
class KeycloakCitizenTokenVerifier implements CitizenTokenVerifier {

    private final JwtDecoder decoder;

    @Autowired
    KeycloakCitizenTokenVerifier(SecurityRealmsProperties realms) {
        this(new SupplierJwtDecoder(() -> {
            if (realms.citizen() == null) {
                throw new IllegalStateException("samanvay.security.citizen must be configured");
            }
            return SecurityConfig.decoder(realms.citizen(), realms.audience());
        }));
    }

    KeycloakCitizenTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public Optional<VerifiedToken> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(rawToken.trim());
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(subject, jwt.getClaims()));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }
}
