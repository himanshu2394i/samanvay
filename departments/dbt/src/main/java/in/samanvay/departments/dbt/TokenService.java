package in.samanvay.departments.dbt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DBT's OAuth2 client-credentials server: checks the client, issues short-lived opaque bearer tokens,
 * and later says whether a presented token is still valid. Secrets are compared in constant time.
 *
 * <p>ponytail: one registered client and an in-memory token map (lost on restart, never pruned);
 * a client table and a token store when more than Samanvay calls or DBT runs in several nodes.
 */
@Component
class TokenService {

    record Issued(String accessToken, long expiresInSeconds) {}

    private final String clientId;
    private final byte[] clientSecret;
    private final String scope;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();

    TokenService(
            @Value("${dbt.oauth.client-id}") String clientId,
            @Value("${dbt.oauth.client-secret}") String clientSecret,
            @Value("${dbt.oauth.scope}") String scope,
            @Value("${dbt.oauth.token-ttl}") Duration ttl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret.getBytes(StandardCharsets.UTF_8);
        this.scope = scope;
        this.ttl = ttl;
    }

    String scope() {
        return scope;
    }

    /** A token for valid client credentials, empty when the client id or secret is wrong. */
    Optional<Issued> issue(String id, String secret) {
        boolean idOk = id != null && MessageDigest.isEqual(clientId.getBytes(StandardCharsets.UTF_8), id.getBytes(StandardCharsets.UTF_8));
        boolean secretOk = secret != null && MessageDigest.isEqual(clientSecret, secret.getBytes(StandardCharsets.UTF_8));
        if (!idOk || !secretOk) {
            return Optional.empty();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, Instant.now().plus(ttl));
        return Optional.of(new Issued(token, ttl.toSeconds()));
    }

    boolean isValid(String token) {
        Instant expiresAt = token == null ? null : tokens.get(token);
        return expiresAt != null && Instant.now().isBefore(expiresAt);
    }
}
