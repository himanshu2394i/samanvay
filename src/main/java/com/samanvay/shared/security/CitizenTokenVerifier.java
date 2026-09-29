package com.samanvay.shared.security;

import java.util.Map;
import java.util.Optional;

/**
 * Verifies a citizen-realm access token presented as DATA (not as the request's own bearer),
 * with exactly the checks the API applies to a bearer: signature from the realm's keys, issuer,
 * expiry, {@code aud} contains the API audience, {@code azp} is one of the realm's allowed
 * clients and {@code typ=Bearer}. Used by identity to accept a department-brokered sign-in as a
 * link proof without weakening any of those checks.
 */
public interface CitizenTokenVerifier {

    /** The verified token, or empty for any token that is malformed, forged, expired or not for this API. */
    Optional<VerifiedToken> verify(String rawToken);

    /** @param claims the token's claims (never logged: they can hold department identifiers) */
    record VerifiedToken(String subject, Map<String, Object> claims) {}
}
