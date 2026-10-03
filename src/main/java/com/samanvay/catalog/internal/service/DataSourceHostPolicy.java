package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Refuses data-source and manifest hosts that are private, loopback or link-local (SSRF). Dev/demo may name hosts that
 * are exempt ({@code samanvay.catalog.allowed-private-hosts}); the match is the exact host name (port ignored, case
 * ignored), never a pattern, and the default is empty.
 */
final class DataSourceHostPolicy {

    private final Function<String, InetAddress> resolver;
    private final Set<String> allowedPrivateHosts;

    DataSourceHostPolicy(Function<String, InetAddress> resolver) {
        this(resolver, Set.of());
    }

    DataSourceHostPolicy(Function<String, InetAddress> resolver, Set<String> allowedPrivateHosts) {
        this.resolver = resolver;
        this.allowedPrivateHosts = allowedPrivateHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    void assertAllowed(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalHostException(String.valueOf(host));
        }
        String h = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        if (allowedPrivateHosts.contains(h.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        if (h.startsWith("127.")
                || h.startsWith("10.")
                || h.startsWith("192.168.")
                || h.startsWith("169.254.")
                || h.startsWith("172.16.")
                || "localhost".equalsIgnoreCase(h)) {
            throw new IllegalHostException(host);
        }
        InetAddress addr = resolver.apply(h);
        if (addr != null
                && (addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress())) {
            throw new IllegalHostException(host);
        }
    }
}
