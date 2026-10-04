package in.samanvay.departments.dbt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Carries "this person passed the password step" from the password page to the one-time-code page without storing anything.
 * The ticket is {@code base64url("personId|expiry|sha256(state|nonce)|random") + "." + base64url(HMAC-SHA256)}: it names the person,
 * expires, and is tied to the exact state and nonce of the login it was issued for, so it cannot be replayed into another login.
 *
 * <p>ponytail: the HMAC key is random per process, so a restart ends pending logins (the citizen just signs in again); one key
 * per instance, not shared between instances.
 */
final class LoginTicket {

    private final byte[] key = new byte[32];
    private final Duration ttl;

    LoginTicket(Duration ttl) {
        new SecureRandom().nextBytes(key);
        this.ttl = ttl;
    }

    String issue(String personId, String state, String nonce, Instant now) {
        byte[] unique = new byte[12];
        new SecureRandom().nextBytes(unique);
        // The random part makes every ticket different, even for the same person and login in the same second, so "one ticket, one use" holds.
        String payload = personId + "|" + now.plus(ttl).getEpochSecond() + "|" + binding(state, nonce) + "|" + HexFormat.of().formatHex(unique);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encoded));
    }

    /** The person the ticket was issued for, if it is genuine, unexpired and for this state and nonce. */
    Optional<String> verify(String ticket, String state, String nonce, Instant now) {
        try {
            if (ticket == null) {
                return Optional.empty();
            }
            String[] parts = ticket.split("\\.", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            byte[] given = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(mac(parts[0]), given)) {
                return Optional.empty();
            }
            String[] payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (payload.length != 4 || now.getEpochSecond() >= Long.parseLong(payload[1])) {
                return Optional.empty();
            }
            boolean sameLogin = MessageDigest.isEqual(payload[2].getBytes(StandardCharsets.UTF_8), binding(state, nonce).getBytes(StandardCharsets.UTF_8));
            return sameLogin ? Optional.of(payload[0]) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty(); // not base64, not a number: not a ticket
        }
    }

    private byte[] mac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String binding(String state, String nonce) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((state + "|" + nonce).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
