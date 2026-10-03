package com.samanvay.identity.internal.proof;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
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
 * Fetches a department's published public keys (JWKS) over HTTP(S), safely: http/https only, no redirects, a hard size
 * cap, a timeout, and private/loopback hosts refused unless {@code samanvay.identity.department-assertion.allow-private-hosts}
 * is set (dev/demo only). Results are cached; a forced refresh (an unknown key ID, i.e. rotation) is allowed at most once
 * per minimum interval per URL so unknown-kid tokens cannot be used to hammer a department. Only public keys are kept.
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
        URI uri = validate(jwksUrl);
        Instant now = clock.instant();
        Cached cached = cache.get(jwksUrl);
        if (cached != null) {
            Duration age = Duration.between(cached.fetchedAt(), now);
            boolean fresh = age.compareTo(cacheTtl) < 0;
            boolean mayForce = age.compareTo(minRefresh) >= 0;
            if ((fresh && !forceRefresh) || (forceRefresh && !mayForce)) {
                return cached.keys();
            }
        }
        JWKSet fetched = fetch(uri);
        cache.put(jwksUrl, new Cached(fetched, now));
        return fetched;
    }

    private URI validate(String jwksUrl) {
        URI uri;
        try {
            uri = URI.create(jwksUrl == null ? "" : jwksUrl.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the department's key URL is not a URL");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("the department's key URL must be http(s) with a host");
        }
        if (!allowPrivateHosts && isPrivate(uri.getHost())) {
            throw new IllegalArgumentException("the department's key URL points at a private or loopback address");
        }
        return uri;
    }

    private static boolean isPrivate(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true; // cannot resolve: do not call it
        }
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
