package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import java.util.function.Function;

final class DataSourceHostPolicy {

    private final Function<String, InetAddress> resolver;

    DataSourceHostPolicy(Function<String, InetAddress> resolver) {
        this.resolver = resolver;
    }

    void assertAllowed(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalHostException(String.valueOf(host));
        }
        String h = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
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
