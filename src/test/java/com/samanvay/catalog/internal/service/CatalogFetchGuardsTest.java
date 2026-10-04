package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.shared.InvalidRequestException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fetching a department's manifest: https unless the host is a named dev/demo exemption, no more than 1 MB read, and a
 * discovery-credential key that cannot be confused between two different hosts.
 */
class CatalogFetchGuardsTest {

    static final InetAddress PUBLIC = DataSourceHostPolicyRangesTest.ip("93.184.216.34");

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CatalogServices svc(Set<String> exempt) {
        return new CatalogServices(null, null, null, null, null, null, null, e -> {}, h -> PUBLIC, exempt);
    }

    @Test
    void plain_http_is_refused_outside_dev_before_anything_is_fetched() {
        assertThatThrownBy(() -> svc(Set.of()).fetch("http://dept.example.gov")).isInstanceOf(InvalidRequestException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> svc(Set.of()).fetch("ftp://dept.example.gov")).isInstanceOf(InvalidRequestException.class).hasMessageContaining("https");
    }

    @Test
    void a_manifest_over_one_megabyte_is_refused_not_buffered() throws Exception {
        byte[] huge = new byte[1024 * 1024 + 10];
        java.util.Arrays.fill(huge, (byte) ' ');
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, huge.length);
            try (var out = ex.getResponseBody()) {
                out.write(huge);
            } catch (java.io.IOException ignored) {
                // the client hangs up once it has read enough
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> svc(Set.of("127.0.0.1")).fetch(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("too large");
    }

    @Test
    void a_chunked_manifest_that_never_ends_is_cut_off_at_the_cap() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked: no Content-Length to check up front
            try (var out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 64; i++) {
                    out.write(chunk);
                }
            } catch (java.io.IOException ignored) {
                // the client hangs up once it has read enough
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> svc(Set.of("127.0.0.1")).fetch(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("too large");
    }

    @Test
    void the_discovery_key_tells_a_dot_from_a_dash_and_a_port_from_a_label() {
        String dotted = CatalogServices.discoverySecretKey(URI.create("https://a.b"));
        String dashed = CatalogServices.discoverySecretKey(URI.create("https://a-b"));
        assertThat(dotted).isEqualTo("manifest-a-b-credential"); // unchanged for the ordinary case
        assertThat(dashed).isNotEqualTo(dotted);
        assertThat(CatalogServices.discoverySecretKey(URI.create("https://a.b:80"))).isNotEqualTo(CatalogServices.discoverySecretKey(URI.create("https://a.b.80")));
        assertThat(CatalogServices.discoverySecretKey(URI.create("https://dept-1.example.gov"))).isNotEqualTo(CatalogServices.discoverySecretKey(URI.create("https://dept.1.example.gov")));
        // an odd host that cannot be encoded safely gets no credential rather than a shared key
        assertThat(CatalogServices.discoverySecretKeys(URI.create("http://[::1]:8080"))).isEmpty();
    }

    @Test
    void the_old_key_is_still_accepted_only_where_it_cannot_belong_to_another_host() {
        // host without a dash, with a port: the old name was unambiguous, so it stays accepted after the new one
        List<String> withPort = CatalogServices.discoverySecretKeys(URI.create("http://127.0.0.1:8091"));
        assertThat(withPort).containsExactly("manifest-127-0-0-1---8091-credential", "manifest-127-0-0-1-8091-credential");
        // no port: old and new are the same name
        assertThat(CatalogServices.discoverySecretKeys(URI.create("https://dept.example.gov"))).containsExactly("manifest-dept-example-gov-credential");
        // a dash in the host: the old name is shared with the dotted host, so it is NOT accepted
        assertThat(CatalogServices.discoverySecretKeys(URI.create("https://a-b:8443"))).containsExactly("manifest-a--b---8443-credential");
    }
}
