package in.samanvay.departments.kit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless proof that this browser signed in at the portal: the cookie value is the signed person, citizen and expiry, so the
 * server keeps nothing. Also issues the short-lived ticket that carries "the password step passed" to the one-time-code step.
 * Both are HMAC-SHA256 over their fields with this process's secret; a changed field, a wrong secret or an old time fails.
 */
public final class PortalSession {

    /** Who is signed in. {@code citizenId} is Samanvay's record of them (changes if two records are merged). */
    public record Session(String personId, UUID citizenId, String name) {}

    public static final Duration SESSION_TTL = Duration.ofHours(8);
    public static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private final byte[] secret;

    public PortalSession(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String issue(Session s, Instant now) {
        return seal("S", now.plus(SESSION_TTL), s.personId(), s.citizenId().toString(), s.name() == null ? "" : s.name());
    }

    public Optional<Session> read(String cookie, Instant now) {
        return open("S", cookie, now, 3).map(f -> new Session(f[0], UUID.fromString(f[1]), f[2].isEmpty() ? null : f[2]));
    }

    public String issueTicket(String personId, Instant now) {
        return seal("T", now.plus(TICKET_TTL), personId);
    }

    public Optional<String> readTicket(String ticket, Instant now) {
        return open("T", ticket, now, 1).map(f -> f[0]);
    }

    private String seal(String kind, Instant expires, String... fields) {
        StringBuilder body = new StringBuilder(kind).append('.').append(expires.getEpochSecond());
        for (String f : fields) {
            body.append('.').append(Base64.getUrlEncoder().withoutPadding().encodeToString(f.getBytes(StandardCharsets.UTF_8)));
        }
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body.toString()));
    }

    private Optional<String[]> open(String kind, String token, Instant now, int fields) {
        try {
            if (token == null) {
                return Optional.empty();
            }
            int cut = token.lastIndexOf('.');
            if (cut < 0) {
                return Optional.empty();
            }
            String body = token.substring(0, cut);
            if (!MessageDigest.isEqual(mac(body), Base64.getUrlDecoder().decode(token.substring(cut + 1)))) {
                return Optional.empty();
            }
            String[] parts = body.split("\\.", -1);
            if (parts.length != 2 + fields || !parts[0].equals(kind) || now.getEpochSecond() >= Long.parseLong(parts[1])) {
                return Optional.empty();
            }
            String[] out = new String[fields];
            for (int i = 0; i < fields; i++) {
                out[i] = new String(Base64.getUrlDecoder().decode(parts[2 + i]), StandardCharsets.UTF_8);
            }
            return Optional.of(out);
        } catch (RuntimeException e) {
            return Optional.empty(); // not base64, not a number, not a UUID: not ours
        }
    }

    private byte[] mac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
