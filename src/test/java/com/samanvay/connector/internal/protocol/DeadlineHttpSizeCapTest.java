package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A department answers with at most 1 MB: more is refused (RESPONSE_TOO_LARGE), never buffered whole. */
class DeadlineHttpSizeCapTest {

    HttpServer server;

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small", ex -> send(ex, 100 * 1024));
        server.createContext("/big", ex -> send(ex, 2 * 1024 * 1024));
        server.createContext("/chunked", ex -> send(ex, 0));
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int length) throws java.io.IOException {
        ex.sendResponseHeaders(200, length); // 0 = chunked
        try (var out = ex.getResponseBody()) {
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'x');
            int total = length == 0 ? 4 * 1024 * 1024 : length;
            for (int sent = 0; sent < total; sent += chunk.length) {
                out.write(chunk, 0, Math.min(chunk.length, total - sent));
            }
        } catch (java.io.IOException ignored) {
            // the client hangs up once it has read enough
        }
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void a_normal_answer_is_returned_whole() {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThat(http.get(uri("/small"))).hasSize(100 * 1024);
    }

    @Test
    void an_answer_over_the_cap_is_refused_whether_or_not_it_declares_its_length() {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThatThrownBy(() -> http.get(uri("/big"))).isInstanceOf(ResponseTooLargeException.class);
        assertThatThrownBy(() -> http.get(uri("/chunked"))).isInstanceOf(ResponseTooLargeException.class);
        assertThatThrownBy(() -> http.post(uri("/big"), "{}", "application/json")).isInstanceOf(ResponseTooLargeException.class);
    }
}
