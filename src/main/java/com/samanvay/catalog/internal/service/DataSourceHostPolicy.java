package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Refuses data-source and manifest hosts that are private, loopback or link-local (SSRF). Dev/demo may name hosts that
 * are exempt ({@code samanvay.catalog.allowed-private-hosts}); the match is the exact host name (port ignored, case
 * ignored, IPv6 brackets ignored), never a pattern, and the default is empty.
 *
 * <p>An IP literal (IPv4, or IPv6 in brackets) is judged as written; a name is judged by EVERY address it resolves to, so a
 * name with one public and one private address is refused. Private means loopback, any-local, link-local (169.254/16,
 * fe80::/10), site-local (10/8, 172.16/12, 192.168/16), IPv6 unique-local (fc00::/7), carrier-grade NAT (100.64/10), 0/8 and
 * multicast.
 *
 * <p>ponytail: the address is checked at registration/fetch time; a name that later resolves elsewhere (DNS rebinding)
 * is not caught here. Pinning the checked address into the HTTP call is the upgrade.
 */
final class DataSourceHostPolicy {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?");
    private static final Pattern FORBIDDEN = Pattern.compile("[@/\\\\?#\\s]");

    private final Function<String, List<InetAddress>> resolver;
    private final Set<String> allowedPrivateHosts;

    DataSourceHostPolicy(Function<String, InetAddress> resolver) {
        this(resolver, Set.of());
    }

    DataSourceHostPolicy(Function<String, InetAddress> resolver, Set<String> allowedPrivateHosts) {
        this(single(resolver), allowedPrivateHosts, null);
    }

    private DataSourceHostPolicy(Function<String, List<InetAddress>> resolver, Set<String> allowedPrivateHosts, Void marker) {
        this.resolver = resolver;
        this.allowedPrivateHosts = allowedPrivateHosts.stream().map(h -> bare(h).toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** A policy whose resolver may return several addresses per name (what DNS really does). */
    static DataSourceHostPolicy ofAll(Function<String, List<InetAddress>> resolver, Set<String> allowedPrivateHosts) {
        return new DataSourceHostPolicy(resolver, allowedPrivateHosts, null);
    }

    /** The production policy: every address the system resolver returns for a name. */
    static DataSourceHostPolicy system(Set<String> allowedPrivateHosts) {
        return ofAll(host -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                return List.of();
            }
        }, allowedPrivateHosts);
    }

    private static Function<String, List<InetAddress>> single(Function<String, InetAddress> resolver) {
        return h -> {
            InetAddress a = resolver.apply(h);
            return a == null ? List.of() : List.of(a);
        };
    }

    /** True when this exact host is on the dev/demo allow-list (private addresses and plain http are accepted for it). */
    boolean isDevExempt(String host) {
        return host != null && !host.isBlank() && allowedPrivateHosts.contains(bare(host).toLowerCase(Locale.ROOT));
    }

    void assertAllowed(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalHostException(String.valueOf(host));
        }
        String trimmed = host.trim();
        String h = bare(trimmed);
        boolean literal6 = h.indexOf(':') >= 0;
        if (FORBIDDEN.matcher(trimmed).find() || h.isEmpty() || !validPort(trimmed, h)
                || !(literal6 ? isIpv6Literal(h) : NAME.matcher(h).matches())) {
            throw new IllegalHostException(host); // credentials, path, spaces, unbalanced brackets: not a host
        }
        if (allowedPrivateHosts.contains(h.toLowerCase(Locale.ROOT))) {
            return;
        }
        if ("localhost".equalsIgnoreCase(h) || h.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            throw new IllegalHostException(host);
        }
        if (literal6 || IPV4.matcher(h).matches()) {
            if (isPrivate(literal(h))) { // a literal is judged as written, with no DNS lookup
                throw new IllegalHostException(host);
            }
            return;
        }
        for (InetAddress addr : resolver.apply(h)) {
            if (isPrivate(addr)) {
                throw new IllegalHostException(host);
            }
        }
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            return b0 == 0 || (b0 == 100 && (b1 & 0xC0) == 64); // 0.0.0.0/8, 100.64.0.0/10
        }
        return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique-local
    }

    /** "host", "host:port", "[v6]", "[v6]:port" or a bare "v6" to just the host, without brackets or port. */
    static String bare(String host) {
        String h = host.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(1, end);
        }
        int colon = h.indexOf(':');
        if (colon >= 0 && colon == h.lastIndexOf(':')) {
            return h.substring(0, colon);
        }
        return h; // no port, or a bare IPv6 literal (several colons)
    }

    /** Whatever follows the host (after "]" or the single ":") must be a port number. */
    private static boolean validPort(String original, String bare) {
        String rest;
        if (original.startsWith("[")) {
            rest = original.substring(Math.min(original.length(), bare.length() + 2));
        } else if (original.indexOf(':') >= 0 && original.indexOf(':') == original.lastIndexOf(':')) {
            rest = original.substring(bare.length());
        } else {
            return true;
        }
        return rest.isEmpty() || rest.matches(":\\d{1,5}");
    }

    private static boolean isIpv6Literal(String h) {
        return h.matches("[0-9A-Fa-f:.]+") && h.indexOf(':') >= 0;
    }

    private static InetAddress literal(String h) {
        try {
            return InetAddress.getByName(h); // a literal: parsed, never resolved
        } catch (UnknownHostException e) {
            throw new IllegalHostException(h);
        }
    }
}
