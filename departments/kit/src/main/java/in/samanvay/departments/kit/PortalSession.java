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
 * Both are HMAC-SHA256 over their fields with this process's secret AND the department's code, so a cookie or ticket made by one
 * department is worthless at another even if two were ever given the same secret; a changed field, a wrong secret or an old time fails.
 */
public final class PortalSession {

    /** Who is signed in. {@code citizenId} is Samanvay's record of them (changes if two records are merged). */
    public record Session(String personId, UUID citizenId, String name) {}

    public static final Duration SESSION_TTL = Duration.ofHours(8);
    public static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private final byte[] secret;
    private final String deptCode;

    public PortalSession(String configuredSecret, String deptCode) {
        this.deptCode = java.util.Objects.requireNonNull(deptCode, "deptCode");
        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * The secret from configuration if there is one; otherwise one made on first start and kept in {@code file} (next to the manifest signing
     * key), so a restart or a re-created container does not sign every citizen out.
     */
    public static PortalSession withSecretFile(String configuredSecret, String deptCode, java.nio.file.Path file) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return new PortalSession(configuredSecret, deptCode);
        }
        try {
            if (java.nio.file.Files.exists(file)) {
                String kept = java.nio.file.Files.readString(file, StandardCharsets.UTF_8).trim();
                if (kept.length() >= 32) {
                    return new PortalSession(kept, deptCode);
                }
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String made = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            SecretFiles.writeOwnerOnly(file, made);
            return new PortalSession(made, deptCode);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot load or create the portal session secret " + file, e);
        }
    }

    public String issue(Session s, Instant now) {
        return seal("S", now.plus(SESSION_TTL), s.personId(), s.citizenId().toString(), s.name() == null ? "" : s.name());
    }

    public Optional<Session> read(String cookie, Instant now) {
        return open("S", cookie, now, 3).map(f -> new Session(f[0], UUID.fromString(f[1]), f[2].isEmpty() ? null : f[2]));
    }

    /** Every ticket is unique (a random part is signed in), so "one ticket, one use" can be enforced even for two sign-ins in one second. */
    public String issueTicket(String personId, Instant now) {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return seal("T", now.plus(TICKET_TTL), personId, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    }

    public Optional<String> readTicket(String ticket, Instant now) {
        return open("T", ticket, now, 2).map(f -> f[0]);
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
            return mac.doFinal((deptCode + "\n" + data).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
