package in.samanvay.departments.kit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows guessing at the two sign-in steps (password, then one-time code), for the portal and for the login used by other
 * departments. Failures are counted in memory, per mobile, per address and per ticket, in a window of ten minutes: five wrong
 * passwords lock a mobile, five wrong codes kill a ticket, and an address gets {@link #PER_IP} failures (more, because many citizens can
 * share one address, for example behind a proxy). A locked key refuses even the right password until the window passes.
 * Also remembers which tickets were already used, so a ticket opens one session at most.
 *
 * <p>ponytail: counters are fixed windows, kept in memory of one server (a restart or a second server starts again from zero), and
 * a counter table that is full stops adding new keys rather than evicting old ones. Use a shared store if departments run in several
 * instances.
 */
public final class SignInThrottle {

    public static final int PER_MOBILE = 5;
    public static final int PER_TICKET = 5;
    public static final int PER_IP = 20;
    public static final Duration WINDOW = Duration.ofMinutes(10);
    /** Longer than any ticket lives, so a forgotten used ticket can no longer be valid. */
    static final Duration USED_TICKET_MEMORY = Duration.ofMinutes(15);
    static final int MAX_KEYS = 10_000;
    static final int MAX_USED_TICKETS = 10_000;

    private record Count(Instant windowEnds, int failures) {}

    private final Clock clock;
    private final Map<String, Count> counts = new ConcurrentHashMap<>();
    private final Map<String, Instant> used = new ConcurrentHashMap<>();
    private Instant nextPrune = Instant.EPOCH;

    public SignInThrottle(Clock clock) {
        this.clock = clock;
    }

    // --- step one: mobile and password --------------------------------------------------------------------

    public boolean credentialsBlocked(String mobile, String ip) {
        return blocked("m:" + mobile, PER_MOBILE) || blocked("ip:" + ip, PER_IP);
    }

    public void credentialsFailed(String mobile, String ip) {
        fail("m:" + mobile);
        fail("ip:" + ip);
    }

    public void credentialsOk(String mobile) {
        counts.remove(key("m:" + mobile));
    }

    // --- step two: the one-time code ----------------------------------------------------------------------

    public boolean codeBlocked(String ticket, String ip) {
        return blocked("t:" + digest(ticket), PER_TICKET) || blocked("ip:" + ip, PER_IP);
    }

    public void codeFailed(String ticket, String ip) {
        fail("t:" + digest(ticket));
        fail("ip:" + ip);
    }

    /** True the first time a ticket is offered, false ever after (and false when too many are being remembered: fail closed). */
    public boolean useTicket(String ticket) {
        Instant now = clock.instant();
        prune(now);
        if (used.size() >= MAX_USED_TICKETS) {
            return false;
        }
        return used.putIfAbsent(digest(ticket), now.plus(USED_TICKET_MEMORY)) == null;
    }

    // --- internals ----------------------------------------------------------------------------------------

    private boolean blocked(String raw, int limit) {
        Count c = counts.get(key(raw));
        return c != null && clock.instant().isBefore(c.windowEnds()) && c.failures() >= limit;
    }

    private void fail(String raw) {
        Instant now = clock.instant();
        prune(now);
        String k = key(raw);
        if (counts.size() >= MAX_KEYS && !counts.containsKey(k)) {
            return;
        }
        counts.merge(k, new Count(now.plus(WINDOW), 1), (old, fresh) ->
                now.isBefore(old.windowEnds()) ? new Count(old.windowEnds(), old.failures() + 1) : fresh);
    }

    private synchronized void prune(Instant now) {
        if (now.isBefore(nextPrune)) {
            return;
        }
        nextPrune = now.plusSeconds(30);
        counts.values().removeIf(c -> !now.isBefore(c.windowEnds()));
        used.values().removeIf(until -> !now.isBefore(until));
    }

    private static String key(String raw) {
        return raw.length() > 80 ? raw.substring(0, 80) : raw;
    }

    private static String digest(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(ticket).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    int trackedKeys() {
        return counts.size();
    }

    int usedTickets() {
        return used.size();
    }
}
