package com.samanvay.identity.internal.proof;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches a department's published public keys (JWKS) over HTTPS, safely: no redirects, a hard size cap, a timeout, and
 * private hosts refused (loopback, link-local, site-local, IPv6 unique-local, CGNAT 100.64/10, benchmarking, multicast and
 * reserved ranges; EVERY address the host resolves to is checked). Plain http and private hosts are allowed only when
 * {@code samanvay.identity.department-assertion.allow-private-hosts} is set (the dev/demo profiles). Results are cached; a
 * cached answer is served without touching DNS again, so a transient DNS failure does not stop logins. A forced refresh (an
 * unknown key ID, i.e. rotation) is allowed at most once per minimum interval per URL so unknown-kid tokens cannot be used to
 * hammer a department. Only public keys are kept.
 *
 * <p>ponytail: the host is checked, then the HTTP client resolves it again to connect, so a DNS answer that changes in between
 * is not caught here; close that with an egress firewall or a pinned-address connector if department hosts are not trusted.
 */
@Component
public class HttpJwksSource implements JwksSource {

    private record Cached(JWKSet keys, Instant fetchedAt) {}

    private final Clock clock;
    private final Duration timeout;
    private final int maxBytes;
    private final Duration cacheTtl;
    private final Duration minRefresh;
    private final boolean allowPrivateHosts;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    HttpJwksSource(
            Clock clock,
            @Value("${samanvay.identity.department-assertion.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this(clock, Duration.ofSeconds(5), 64 * 1024, Duration.ofMinutes(5), Duration.ofSeconds(30), allowPrivateHosts);
    }

    public HttpJwksSource(
            Clock clock, Duration timeout, int maxBytes, Duration cacheTtl, Duration minRefresh, boolean allowPrivateHosts) {
        this.clock = clock;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.cacheTtl = cacheTtl;
        this.minRefresh = minRefresh;
        this.allowPrivateHosts = allowPrivateHosts;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public JWKSet keys(String jwksUrl, boolean forceRefresh) {
        Instant now = clock.instant();
        Cached cached = jwksUrl == null ? null : cache.get(jwksUrl);
        if (cached != null) {
            Duration age = Duration.between(cached.fetchedAt(), now);
            boolean fresh = age.compareTo(cacheTtl) < 0;
            boolean mayForce = age.compareTo(minRefresh) >= 0;
            if ((fresh && !forceRefresh) || (forceRefresh && !mayForce)) {
                return cached.keys(); // checked when it was fetched; no DNS lookup here
            }
        }
        URI uri = validate(jwksUrl);
        JWKSet fetched = fetch(uri);
        cache.put(jwksUrl, new Cached(fetched, now));
        return fetched;
    }

    URI validate(String jwksUrl) {
        URI uri;
        try {
            uri = URI.create(jwksUrl == null ? "" : jwksUrl.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the department's key URL is not a URL");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("the department's key URL must be https with a host");
        }
        if (!allowPrivateHosts) {
            if (!"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("the department's key URL must be https");
            }
            if (isPrivate(uri.getHost())) {
                throw new IllegalArgumentException("the department's key URL points at a private or loopback address");
            }
        }
        return uri;
    }

    /** Every address the host resolves to; overridable so tests need no DNS. */
    InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    private boolean isPrivate(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            for (InetAddress a : resolve(host)) {
                if (isPrivate(a)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return true; // cannot resolve: do not call it
        }
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            return b0 == 0 // "this network"
                    || (b0 == 100 && (b1 & 0xC0) == 64) // CGNAT 100.64.0.0/10
                    || (b0 == 198 && (b1 & 0xFE) == 18) // benchmarking 198.18.0.0/15
                    || b0 >= 240; // reserved and broadcast
        }
        int b0 = b[0] & 0xFF;
        return (b0 & 0xFE) == 0xFC // unique local fc00::/7
                || (b0 == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B); // NAT64 64:ff9b::/96 can reach internal IPv4
    }

    private JWKSet fetch(URI uri) {
        try {
            HttpResponse<InputStream> r = http.send(
                    HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("the department's keys answered HTTP " + r.statusCode());
                }
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IllegalStateException("the department's keys response is too large");
                }
                return JWKSet.parse(new String(bytes, StandardCharsets.UTF_8)).toPublicJWKSet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted fetching the department's keys");
        } catch (IOException | java.text.ParseException e) {
            throw new IllegalStateException("could not fetch the department's keys");
        }
    }
}
