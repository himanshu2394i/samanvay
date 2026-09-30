package com.samanvay.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.shared.InvalidRequestException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo/dev only: a one-click sign-in for judges walking the demo, so they never face MFA. It mints a
 * short-lived access token for a chosen demo role, signed with a keypair generated at startup, and
 * registers a matching {@code issuer -> AuthenticationManager} so the API accepts it — exactly like a
 * Keycloak token (same {@code aud=samanvay-api}, {@code azp}, {@code typ=Bearer}, role claims), but
 * without touching Keycloak or its hardened flows. Gated to the {@code dev}/{@code demo} profiles, so
 * a normal boot has neither the endpoint nor the trusted issuer, and the token is refused there.
 */
@Configuration
@Profile({"dev", "demo"})
class DemoSignIn {

    static final String STAFF_ISSUER = "samanvay-demo-staff";
    static final String CITIZEN_ISSUER = "samanvay-demo-citizen";
    /** The synthetic client id the tokens carry as {@code azp}; the demo issuer accepts only this one. */
    static final String CLIENT = "samanvay-demo";

    private final RSAKey key;

    DemoSignIn() {
        try {
            this.key = new RSAKeyGenerator(2048).keyID("demo").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate the demo sign-in key", e);
        }
    }

    /** A demo identity: which issuer it comes from, its username, roles and (staff) department. */
    private record DemoUser(String issuer, String username, String name, List<String> roles, String department) {}

    private static DemoUser userFor(String role) {
        return switch (role == null ? "" : role.trim().toLowerCase(Locale.ROOT)) {
            case "admin" -> new DemoUser(STAFF_ISSUER, "demo-admin", "Demo Admin", List.of("admin"), null);
            case "officer" -> new DemoUser(STAFF_ISSUER, "demo-officer", "Demo Officer", List.of("officer"), "SCHOLARSHIP");
            case "reviewer" -> new DemoUser(STAFF_ISSUER, "demo-reviewer", "Demo Reviewer", List.of("reviewer"), null);
            case "citizen" -> new DemoUser(CITIZEN_ISSUER, "demo-citizen", "Demo Citizen", List.of("citizen"), null);
            default -> throw new InvalidRequestException("unknown demo role: " + role);
        };
    }

    String mint(String role) {
        DemoUser u = userFor(role);
        // Citizens get a fresh identity on every sign-in, so the "connect a department" step always
        // starts clean (nothing linked yet) and one session never inherits another's connections.
        // Staff keep a stable identity (a shared operator view is fine and carries no personal links).
        String subject = u.roles().contains("citizen")
                ? u.username() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                : u.username();
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(u.issuer())
                .subject(subject)
                .audience("samanvay-api")
                .jwtID(java.util.UUID.randomUUID().toString()) // the session proof consent binds a grant to (like a Keycloak jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(12)))) // a long demo session
                .claim("typ", "Bearer")
                .claim("azp", CLIENT)
                .claim("preferred_username", u.username())
                .claim("name", u.name())
                .claim("realm_access", Map.of("roles", u.roles()));
        if (u.department() != null) {
            claims.claim(KeycloakJwtConverter.DEPARTMENT_CLAIM, u.department());
        }
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot mint the demo token", e);
        }
    }

    /** Trust the demo issuers alongside the two Keycloak realms — demo profile only. */
    @Bean
    AdditionalAuthManagers demoAuthManagers(SecurityRealmsProperties realms) {
        RSAPublicKey pub;
        try {
            pub = key.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot read the demo public key", e);
        }
        Map<String, AuthenticationManager> byIssuer = new LinkedHashMap<>();
        byIssuer.put(STAFF_ISSUER, manager(pub, STAFF_ISSUER, realms.audience(), KeycloakJwtConverter.RealmKind.STAFF));
        byIssuer.put(CITIZEN_ISSUER, manager(pub, CITIZEN_ISSUER, realms.audience(), KeycloakJwtConverter.RealmKind.CITIZEN));
        return () -> byIssuer;
    }

    private static AuthenticationManager manager(
            RSAPublicKey pub, String issuer, String audience, KeycloakJwtConverter.RealmKind kind) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(pub).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new KeycloakTokenValidator(audience, List.of(CLIENT))));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(new KeycloakJwtConverter(kind));
        return provider::authenticate;
    }

    /** {@code POST /ui/demo-signin {"role":"admin|officer|reviewer|citizen"}} -> a bearer token. */
    @RestController
    @Profile({"dev", "demo"})
    static class DemoSignInController {

        private final DemoSignIn demo;

        DemoSignInController(DemoSignIn demo) {
            this.demo = demo;
        }

        @PostMapping("/ui/demo-signin")
        Map<String, String> signin(@RequestBody Body body) {
            return Map.of("access_token", demo.mint(body.role()), "token_type", "Bearer");
        }

        record Body(String role) {}
    }
}
