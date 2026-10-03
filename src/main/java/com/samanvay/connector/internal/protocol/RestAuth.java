package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.AuthSpec;
import com.samanvay.connector.internal.source.SourceCredentials;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the auth scheme a department declared (its manifest {@code auth} block) to a REST call: API key (header or
 * query), HTTP Basic, or OAuth2 client credentials (token fetched, cached per source until near expiry, sent as a
 * Bearer). The declared parameter NAMES come from the {@link AuthSpec}; the VALUES come from the SecretStore by those
 * names ({@link SourceCredentials#params}). No value ever appears in an exception message or a log line.
 *
 * <p>A source with scheme NONE (every source onboarded before manifests) is untouched.
 *
 * <p>ponytail: tokens are cached in memory per source (lost on restart, not shared between nodes); no HMAC request
 * signing or client certificates yet (see docs/FINAL-CHANGES.md section 13).
 */
@Component
class RestAuth {

    /** Signs a finished request: method, the raw path + query as sent, and the body text; returns the headers to add. */
    @FunctionalInterface
    interface Signer {
        Map<String, String> sign(String method, String rawPathAndQuery, String body);
    }

    /**
     * Extra headers and query parameters for the business call, plus (when the scheme needs them) a {@link Signer} that
     * signs the finished request and the TLS identity (client certificate) to call over.
     */
    record Applied(Map<String, String> headers, Map<String, String> query, Signer signer, SSLContext tls) {
        static final Applied NONE = new Applied(Map.of(), Map.of(), null, null);

        Applied(Map<String, String> headers, Map<String, String> query) {
            this(headers, query, null, null);
        }
    }

    private record Token(String value, Instant expiresAt) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final SourceCredentials credentials;
    private final DeadlineHttp http;
    private final String scheme;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Clock clock;
    /** SSL contexts by source and a digest of its certificate material, so a rotated certificate takes effect at once. */
    private final Map<String, SSLContext> tlsContexts = new ConcurrentHashMap<>();

    @Autowired
    RestAuth(SourceCredentials credentials, DeadlineHttp http) {
        this(credentials, http, "https");
    }

    /** Test seam: lets a test point the token call at a plain-HTTP in-JVM server. */
    RestAuth(SourceCredentials credentials, DeadlineHttp http, String scheme) {
        this(credentials, http, scheme, Clock.systemUTC());
    }

    /** Test seam: a fixed clock makes request signing reproducible. */
    RestAuth(SourceCredentials credentials, DeadlineHttp http, String scheme, Clock clock) {
        this.credentials = credentials;
        this.http = http;
        this.scheme = scheme;
        this.clock = clock;
    }

    Applied apply(AdapterRequest request, String origin) {
        AuthSpec spec = AuthSpec.of(request.authType(), request.authSpecJson());
        if (spec.isNone()) {
            return Applied.NONE;
        }
        String source = request.dataSourceCode();
        String code = SourceCredentials.credentialCode(source, request.authConfigRef());
        Map<String, String> secret = credentials.params(code);
        return switch (spec.scheme()) {
            case "API_KEY" -> apiKey(source, code, spec, secret);
            case "BASIC" -> basic(source, code, secret);
            case "OAUTH2_CLIENT" -> oauth(source, code, spec, secret, origin);
            case "HMAC_SHA256" -> hmac(source, code, secret);
            case "MTLS" -> mtls(source, code, secret);
            default -> throw new IllegalConnectorConfigurationException(
                    "REST source '" + source + "' has an unsupported auth scheme '" + spec.scheme() + "'");
        };
    }

    private Applied apiKey(String source, String code, AuthSpec spec, Map<String, String> secret) {
        if (spec.parameters().isEmpty()) {
            throw new IllegalConnectorConfigurationException("REST source '" + source + "' is API_KEY but declares no parameters");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        for (AuthSpec.Param p : spec.parameters()) {
            String value = require(source, code, secret, p.name());
            if ("query".equals(p.in())) {
                query.put(p.name(), value);
            } else {
                headers.put(p.name(), headerSafe(source, p.name(), value));
            }
        }
        return new Applied(headers, query);
    }

    /**
     * HMAC-SHA256 request signing (docs/contracts/hmac-request-signing.md): headers {@code X-Key-Id}, {@code X-Timestamp}
     * (epoch seconds) and {@code X-Signature} = base64 HMAC of {@code METHOD \n path+query \n timestamp \n hex(sha256(body))}.
     * The secret never travels.
     */
    private Applied hmac(String source, String code, Map<String, String> secret) {
        String keyId = headerSafe(source, "key_id", require(source, code, secret, "key_id"));
        byte[] key = require(source, code, secret, "secret").getBytes(StandardCharsets.UTF_8);
        Signer signer = (method, rawPathAndQuery, body) -> {
            try {
                long ts = clock.instant().getEpochSecond();
                String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                String sig = Base64.getEncoder().encodeToString(mac.doFinal((method + "\n" + rawPathAndQuery + "\n" + ts + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
                return Map.of("X-Key-Id", keyId, "X-Timestamp", String.valueOf(ts), "X-Signature", sig);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalConnectorConfigurationException("REST source '" + source + "' could not sign the request");
            }
        };
        return new Applied(Map.of(), Map.of(), signer, null);
    }

    /**
     * Mutual TLS: the client certificate (a base64 PKCS#12 and its password) is presented to the department; an optional
     * {@code server_ca} (PEM) adds a private CA to what is trusted. The SSL context is cached per source and rebuilt when the
     * credential changes.
     */
    private Applied mtls(String source, String code, Map<String, String> secret) {
        String p12 = require(source, code, secret, "client_certificate");
        String password = require(source, code, secret, "client_certificate_password");
        String ca = secret.get("server_ca");
        String fingerprint = source + ":" + digest(p12 + "\u0000" + password + "\u0000" + (ca == null ? "" : ca));
        SSLContext ctx = tlsContexts.computeIfAbsent(fingerprint, f -> buildTls(source, p12, password, ca));
        return new Applied(Map.of(), Map.of(), null, ctx);
    }

    private static SSLContext buildTls(String source, String p12Base64, String password, String caPem) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(p12Base64.trim())), password.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password.toCharArray());
            javax.net.ssl.TrustManager[] trust = null; // default: the JVM's trusted authorities
            if (caPem != null && !caPem.isBlank()) {
                KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
                ts.load(null, null);
                int i = 0;
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)))) {
                    ts.setCertificateEntry("ca" + i++, c);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                trust = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), trust, null);
            return ctx;
        } catch (Exception e) {
            // never echo the exception text or the secret: it may quote the certificate or password
            throw new IllegalConnectorConfigurationException("REST source '" + source
                    + "': the client certificate could not be loaded (expected a base64 PKCS#12, its password, and an optional PEM server_ca)");
        }
    }

    private static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Applied basic(String source, String code, Map<String, String> secret) {
        String user = require(source, code, secret, "username");
        String pass = require(source, code, secret, "password");
        String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return new Applied(Map.of("Authorization", "Basic " + token), Map.of());
    }

    private Applied oauth(String source, String code, AuthSpec spec, Map<String, String> secret, String origin) {
        String clientId = require(source, code, secret, "client_id");
        String clientSecret = require(source, code, secret, "client_secret");
        if (spec.tokenUrl() == null || !spec.tokenUrl().startsWith("/")) {
            // Only a path on the department's own host: never an absolute URL from a manifest (SSRF).
            throw new IllegalConnectorConfigurationException("REST source '" + source + "' needs an OAuth2 tokenUrl that is a path on its host");
        }
        Token cached = tokens.get(source);
        if (cached == null || !Instant.now().isBefore(cached.expiresAt())) {
            cached = fetchToken(source, spec, clientId, clientSecret, origin);
            tokens.put(source, cached);
        }
        return new Applied(Map.of("Authorization", "Bearer " + cached.value()), Map.of());
    }

    private Token fetchToken(String source, AuthSpec spec, String clientId, String clientSecret, String origin) {
        StringBuilder form = new StringBuilder("grant_type=client_credentials")
                .append("&client_id=").append(enc(clientId))
                .append("&client_secret=").append(enc(clientSecret));
        if (!spec.scopes().isEmpty()) {
            form.append("&scope=").append(enc(String.join(" ", spec.scopes())));
        }
        String raw = http.post(URI.create(origin + spec.tokenUrl()), form.toString(), "application/x-www-form-urlencoded");
        JsonNode n;
        try {
            n = JSON.readTree(raw == null ? "{}" : raw);
        } catch (RuntimeException e) {
            throw new IllegalConnectorConfigurationException("REST source '" + source + "': the token endpoint did not return JSON");
        }
        JsonNode access = n.get("access_token");
        if (access == null || access.asString().isBlank()) {
            throw new IllegalConnectorConfigurationException("REST source '" + source + "': the token endpoint returned no access_token");
        }
        Duration ttl = n.get("expires_in") == null ? DEFAULT_TTL : Duration.ofSeconds(n.get("expires_in").asLong(DEFAULT_TTL.toSeconds()));
        return new Token(access.asString(), Instant.now().plus(ttl).minus(SKEW));
    }

    private static String require(String source, String code, Map<String, String> secret, String name) {
        return SourceCredentials.requireParam(source, code, secret, name);
    }

    /** A value that would break out of its header line (CR/LF) is refused, so a bad secret cannot inject headers. */
    private static String headerSafe(String source, String name, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalConnectorConfigurationException("source '" + source + "' credential parameter '" + name + "' has an illegal character");
        }
        return value;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
