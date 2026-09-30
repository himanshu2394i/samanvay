package com.samanvay.shared.security;

import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * Extra {@code issuer -> AuthenticationManager} entries merged into the resource server's issuer
 * resolver. There is no such bean in a normal boot, so the API trusts only the two Keycloak realms;
 * the demo profile contributes one (see {@code DemoSignIn}) so a demo-minted token is accepted.
 */
public interface AdditionalAuthManagers {
    Map<String, AuthenticationManager> byIssuer();
}
