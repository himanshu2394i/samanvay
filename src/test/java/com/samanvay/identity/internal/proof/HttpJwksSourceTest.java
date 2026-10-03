package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A department's published keys are fetched safely: bounded, cached, rate-limited on refresh, never from private hosts by default. */
class HttpJwksSourceTest {

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    volatile String body;
    volatile int status = 200;
    ECKey key;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));

    static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @BeforeEach
    void up() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        body = new JWKSet(key.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks.json", ex -> {
            hits.incrementAndGet();
            if (status == 302) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
                ex.sendResponseHeaders(302, -1);
            } else {
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
    }

    HttpJwksSource source(boolean allowPrivate) {
        return new HttpJwksSource(clock, Duration.ofSeconds(3), 64 * 1024, Duration.ofMinutes(5), Duration.ofSeconds(30), allowPrivate);
    }

    @Test
    void the_published_keys_are_fetched_and_parsed() {
        JWKSet set = source(true).keys(url(), false);
        assertThat(set.getKeyByKeyId("k1")).isNotNull();
        assertThat(set.getKeyByKeyId("k1").toECKey().isPrivate()).isFalse();
    }

    @Test
    void keys_are_cached_until_the_ttl_then_fetched_again() {
        var s = source(true);
        s.keys(url(), false);
        s.keys(url(), false);
        assertThat(hits.get()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(6));
        s.keys(url(), false);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void a_forced_refresh_refetches_but_not_more_often_than_the_minimum_interval() {
        var s = source(true);
        s.keys(url(), false);
        clock.advance(Duration.ofSeconds(31));
        s.keys(url(), true);
        assertThat(hits.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(5));
        s.keys(url(), true); // too soon: served from cache so unknown-kid tokens cannot hammer the department
        assertThat(hits.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(31));
        s.keys(url(), true);
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void a_non_200_answer_is_an_error_not_an_empty_key_set() {
        status = 500;
        assertThatThrownBy(() -> source(true).keys(url(), false)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_redirect_is_not_followed() {
        status = 302;
        assertThatThrownBy(() -> source(true).keys(url(), false)).isInstanceOf(RuntimeException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void an_oversized_or_invalid_body_is_refused() {
        body = "x".repeat(70 * 1024);
        assertThatThrownBy(() -> source(true).keys(url(), false)).isInstanceOf(RuntimeException.class);
        body = "{not json";
        assertThatThrownBy(() -> new HttpJwksSource(clock, Duration.ofSeconds(3), 64 * 1024, Duration.ofMinutes(5), Duration.ofSeconds(30), true)
                .keys(url(), false)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void private_hosts_are_refused_before_any_request_unless_explicitly_allowed_for_dev() {
        assertThatThrownBy(() -> source(false).keys(url(), false)).isInstanceOf(RuntimeException.class);
        assertThat(hits.get()).isZero();
        assertThat(source(true).keys(url(), false).getKeyByKeyId("k1")).isNotNull();
    }

    @Test
    void only_http_and_https_urls_are_accepted() {
        assertThatThrownBy(() -> source(true).keys("file:///etc/passwd", false)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> source(true).keys("not a url", false)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> source(true).keys(null, false)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void secret_or_private_key_material_in_the_published_set_is_never_returned() throws Exception {
        OctetSequenceKey secret = new OctetSequenceKeyGenerator(256).keyID("hmac").generate();
        body = new JWKSet(java.util.List.of(key, secret)).toString(false); // includes the private EC key and an HMAC secret
        JWKSet set = source(true).keys(url(), false);
        assertThat(set.getKeyByKeyId("hmac")).isNull();
        assertThat(set.getKeyByKeyId("k1").toECKey().isPrivate()).isFalse();
    }
}
