package com.samanvay.shared.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mints Keycloak-shaped access tokens - the same claims the committed realm
 * exports produce: {@code aud=samanvay-api}, {@code typ=Bearer}, {@code azp}
 * of the realm's real UI client (or the department client's id) - signed with throwaway, per-JVM RSA keys,
 * so integration tests exercise the real resource-server validation (signature,
 * issuer, expiry, role mapping) without a running Keycloak.
 *
 * <p>Each realm has its own key; the public halves are written to temp PEM
 * files and wired in through {@code samanvay.security.<realm>.public-key-location}
 * by {@link PostgresIntegrationTest}. Nothing here is a real secret.
 */
public final class TestTokens {

    public static final String STAFF_ISSUER = "https://test-idp.invalid/realms/samanvay-staff";
    public static final String CITIZEN_ISSUER = "https://test-idp.invalid/realms/samanvay-citizen";
    public static final String AUDIENCE = "samanvay-api";
    public static final String STAFF_UI_CLIENT = "samanvay-staff-ui";
    public static final String CITIZEN_UI_CLIENT = "samanvay-citizen-ui";
    /** Staff-realm azp allow-list used by the ITs: the real UI + department client and test department clients. */
    public static final List<String> STAFF_CLIENTS = List.of(
            STAFF_UI_CLIENT, "dept-scholarship-dev", "dept-scholarship-it", "dept-narrow", "dept-x", "d", "matrix-dept");

    private static final KeyPair STAFF_KEY = rsa();
    private static final KeyPair CITIZEN_KEY = rsa();
    private static final KeyPair ROGUE_KEY = rsa();

    public static final Path STAFF_PUBLIC_KEY_PEM = writePem("staff", STAFF_KEY);
    public static final Path CITIZEN_PUBLIC_KEY_PEM = writePem("citizen", CITIZEN_KEY);

    private TestTokens() {}

    public static String citizen(String subject) {
        return person(CITIZEN_ISSUER, CITIZEN_KEY, CITIZEN_UI_CLIENT, subject, List.of("citizen"));
    }

    /** Department every test officer / department client belongs to unless a test says otherwise. */
    public static final String DEFAULT_DEPARTMENT = "SCHOLARSHIP";

    public static String officer(String subject) {
        return officerOf(subject, DEFAULT_DEPARTMENT);
    }

    /** An officer whose token carries {@code department=<department>} (null: no department claim). */
    public static String officerOf(String subject, String department) {
        JWTClaimsSet.Builder b = base(STAFF_ISSUER, subject)
                .claim("azp", STAFF_UI_CLIENT)
                .claim("preferred_username", subject)
                .claim("realm_access", Map.of("roles", List.of("officer")))
                .claim("scope", "openid profile email");
        if (department != null) {
            b.claim("department", department);
        }
        return sign(STAFF_KEY, b.build());
    }

    public static String reviewer(String subject) {
        return person(STAFF_ISSUER, STAFF_KEY, STAFF_UI_CLIENT, subject, List.of("reviewer"));
    }

    public static String admin(String subject) {
        return person(STAFF_ISSUER, STAFF_KEY, STAFF_UI_CLIENT, subject, List.of("admin"));
    }

    /** Client-credentials token of a department integration, one {@code source:<code>} scope per data source. */
    public static String department(String clientId, String... dataSources) {
        return departmentOf(clientId, DEFAULT_DEPARTMENT, dataSources);
    }

    /** Department client token with {@code department=<department>} (the realm's hardcoded client claim). */
    public static String departmentOf(String clientId, String department, String... dataSources) {
        StringBuilder scope = new StringBuilder("profile email");
        for (String ds : dataSources) {
            scope.append(" source:").append(ds);
        }
        return sign(STAFF_KEY, base(STAFF_ISSUER, "service-account-" + clientId)
                .claim("azp", clientId)
                .claim("client_id", clientId)
                .claim("preferred_username", "service-account-" + clientId)
                .claim("realm_access", Map.of("roles", List.of("department", "default-roles-samanvay-staff")))
                .claim("scope", scope.toString())
                .claim("department", department)
                .build());
    }

    /** A staff-realm token carrying arbitrary realm roles (for negative tests). */
    public static String staffWithRoles(String subject, List<String> roles) {
        return person(STAFF_ISSUER, STAFF_KEY, STAFF_UI_CLIENT, subject, roles);
    }

    /** A citizen-realm token carrying arbitrary realm roles (for negative tests). */
    public static String citizenRealmWithRoles(String subject, List<String> roles) {
        return person(CITIZEN_ISSUER, CITIZEN_KEY, CITIZEN_UI_CLIENT, subject, roles);
    }

    /** Staff issuer, but signed by a key the app does not trust. */
    public static String forgedOfficer(String subject) {
        return person(STAFF_ISSUER, ROGUE_KEY, STAFF_UI_CLIENT, subject, List.of("officer"));
    }

    /** Correctly signed but already expired. */
    public static String expiredOfficer(String subject) {
        Instant past = Instant.now().minusSeconds(3600);
        return sign(STAFF_KEY, new JWTClaimsSet.Builder()
                .issuer(STAFF_ISSUER)
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(past.minusSeconds(300)))
                .expirationTime(Date.from(past))
                .audience(AUDIENCE)
                .claim("typ", "Bearer")
                .claim("azp", STAFF_UI_CLIENT)
                .claim("realm_access", Map.of("roles", List.of("officer")))
                .build());
    }

    /** Signed with the staff key but claiming an issuer the app does not trust. */
    public static String unknownIssuerOfficer(String subject) {
        return sign(STAFF_KEY, base("https://evil.invalid/realms/samanvay-staff", subject)
                .claim("realm_access", Map.of("roles", List.of("officer")))
                .build());
    }

    /** A valid officer token except that {@code aud} names some other API. */
    public static String officerForOtherAudience(String subject) {
        return sign(STAFF_KEY, base(STAFF_ISSUER, subject)
                .audience(List.of("account", "some-other-api"))
                .claim("azp", STAFF_UI_CLIENT)
                .claim("realm_access", Map.of("roles", List.of("officer")))
                .build());
    }

    /**
     * A staff-realm officer token issued to a client that is not on the allow-list,
     * e.g. {@code admin-cli} via a password grant. Even with the right audience it is refused.
     */
    public static String officerViaClient(String subject, String azp) {
        return sign(STAFF_KEY, base(STAFF_ISSUER, subject)
                .claim("azp", azp)
                .claim("realm_access", Map.of("roles", List.of("officer")))
                .build());
    }

    /** A citizen token without any {@code azp}. */
    public static String citizenWithoutAzp(String subject) {
        return sign(CITIZEN_KEY, base(CITIZEN_ISSUER, subject)
                .claim("realm_access", Map.of("roles", List.of("citizen")))
                .build());
    }

    /** Correctly signed, right audience and client, but {@code typ} is not Bearer (e.g. an ID token). */
    public static String citizenWithTyp(String subject, String typ) {
        return sign(CITIZEN_KEY, base(CITIZEN_ISSUER, subject)
                .claim("typ", typ)
                .claim("azp", CITIZEN_UI_CLIENT)
                .claim("realm_access", Map.of("roles", List.of("citizen")))
                .build());
    }

    /** Parses the {@code jti} back out of a token minted here. */
    public static String jti(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String person(String issuer, KeyPair key, String azp, String subject, List<String> roles) {
        return sign(key, base(issuer, subject)
                .claim("azp", azp)
                .claim("preferred_username", subject)
                .claim("realm_access", Map.of("roles", roles))
                .claim("scope", "openid profile email")
                .build());
    }

    private static JWTClaimsSet.Builder base(String issuer, String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .audience(AUDIENCE)
                .claim("typ", "Bearer");
    }

    private static String sign(KeyPair key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePem(String name, KeyPair key) {
        try {
            Path file = Files.createTempFile("samanvay-test-" + name + "-", ".pem");
            file.toFile().deleteOnExit();
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getPublic().getEncoded());
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
