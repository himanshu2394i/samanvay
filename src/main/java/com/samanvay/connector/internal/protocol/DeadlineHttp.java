package com.samanvay.connector.internal.protocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * HTTP GET and POST for department connectors, bounded by one TOTAL deadline covering connect, send,
 * headers and the full body.
 *
 * <p>Why not a socket read timeout or {@code HttpRequest.timeout()}: a read timeout restarts every
 * time a byte arrives, so a department trickling its body can hold the call for ever, and the
 * request timeout stops counting once the headers are in. Here the body handler buffers the whole
 * body before the future completes, and the caller waits on that future for at most the time left
 * on the {@link ExchangeDeadline} (or {@code samanvay.connector.total-timeout} when none is open).
 *
 * <p>On timeout the exchange is actively cancelled: {@code cancel(true)} on the very future
 * {@code sendAsync} returned, which aborts the request and closes its connection (JDK 16+). This
 * uses a timed {@code get} rather than {@code orTimeout}: {@code orTimeout} would complete that
 * same future exceptionally first, so a later {@code cancel} would no longer reach the transfer.
 * Anything that arrives after the deadline is discarded with the cancelled future.
 */
@Component
public class DeadlineHttp {

    /** A department answers with a document, not a download: more than this is refused. ponytail: fixed; make it a property if a real source needs more. */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final HttpClient client;
    private final Duration connectTimeout;
    private final Duration totalTimeout;
    /** One client per TLS identity (a source's client certificate), so a certificate is never used for another source. */
    private final Map<SSLContext, HttpClient> tlsClients = new ConcurrentHashMap<>();

    public DeadlineHttp(
            @Value("${samanvay.connector.timeout:PT10S}") Duration connectTimeout,
            @Value("${samanvay.connector.total-timeout:PT10S}") Duration totalTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.connectTimeout = connectTimeout;
        this.totalTimeout = totalTimeout;
    }

    public String get(URI uri) {
        return get(uri, Map.of());
    }

    /** GET with extra request headers (e.g. an API key or bearer token), under the same total deadline. */
    public String get(URI uri, Map<String, String> headers) {
        return get(uri, headers, null);
    }

    /** As above, over the given TLS identity (client certificate and trust); null means the default client. */
    public String get(URI uri, Map<String, String> headers, SSLContext tls) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET();
        headers.forEach(b::header);
        return send(uri, b.build(), clientFor(tls));
    }

    /** POST {@code body} (UTF-8) with the given content type, under the same total deadline as {@link #get}. */
    public String post(URI uri, String body, String contentType) {
        return post(uri, body, contentType, Map.of());
    }

    /** POST with extra request headers, under the same total deadline. */
    public String post(URI uri, String body, String contentType, Map<String, String> headers) {
        return post(uri, body, contentType, headers, null);
    }

    /** As above, over the given TLS identity; null means the default client. */
    public String post(URI uri, String body, String contentType, Map<String, String> headers, SSLContext tls) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return send(uri, b.build(), clientFor(tls));
    }

    private HttpClient clientFor(SSLContext tls) {
        if (tls == null) {
            return client;
        }
        return tlsClients.computeIfAbsent(tls, c -> HttpClient.newBuilder().connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1).sslContext(c).build());
    }

    private String send(URI uri, HttpRequest request) {
        return send(uri, request, client);
    }

    private String send(URI uri, HttpRequest request, HttpClient client) {
        Duration left = ExchangeDeadline.remaining().orElse(totalTimeout);
        if (left.isZero() || left.isNegative()) {
            throw new ExchangeDeadlineExceededException("connector deadline already passed before calling " + uri.getHost());
        }
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, com.samanvay.shared.CappedBody.upTo(MAX_RESPONSE_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new ExchangeDeadlineExceededException(
                    "connector exchange with " + uri.getHost() + " exceeded its total deadline; cancelled");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResourceAccessException("interrupted calling " + uri.getHost());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw new ResourceAccessException("I/O error calling " + uri.getHost() + ": " + io.getMessage(), io);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
        int status = response.statusCode();
        if (status >= 500) {
            throw HttpServerErrorException.create(
                    HttpStatusCode.valueOf(status), "department error", new HttpHeaders(), null, null);
        }
        if (status >= 400) {
            throw HttpClientErrorException.create(
                    HttpStatusCode.valueOf(status), "department refused", new HttpHeaders(), null, null);
        }
        byte[] body = response.body();
        if (body != null && body.length > MAX_RESPONSE_BYTES) {
            throw new ResponseTooLargeException("the department of " + uri.getHost() + " answered with more than " + MAX_RESPONSE_BYTES / 1024 + " KB");
        }
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}
