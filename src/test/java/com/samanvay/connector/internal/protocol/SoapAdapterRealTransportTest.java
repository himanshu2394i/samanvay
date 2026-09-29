package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

/** SOAP over real HTTP: the rendered envelope is POSTed and the response body is parsed (XXE-safe). */
class SoapAdapterRealTransportTest {

    static final String TEMPLATE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<GetMarks><studentId>{{studentId}}</studentId></GetMarks></soap:Body></soap:Envelope>";
    static final String RESPONSE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<GetMarksResponse><percentage>87</percentage><board>CBSE</board></GetMarksResponse>"
                    + "</soap:Body></soap:Envelope>";

    HttpServer server;
    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final AtomicReference<String> receivedMethod = new AtomicReference<>();
    final AtomicReference<String> receivedContentType = new AtomicReference<>();
    final AtomicReference<String> receivedPath = new AtomicReference<>();
    volatile int status = 200;
    volatile String responseBody = RESPONSE;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    SoapAdapter adapter() {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new SoapAdapter(new MockDepartmentBackend(), http, "http");
    }

    AdapterRequest request(String template) {
        return new AdapterRequest("BOARD_MARKS", "SOAP", "127.0.0.1:" + server.getAddress().getPort(),
                "/marks/service", template, Map.of("studentId", "S-1&2"), null);
    }

    @Test
    void postsRenderedEnvelopeAndParsesResponse() {
        AdapterResponse response = adapter().execute(request(TEMPLATE));

        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedPath.get()).isEqualTo("/marks/service");
        assertThat(receivedContentType.get()).isEqualTo("text/xml; charset=utf-8");
        assertThat(receivedBody.get())
                .contains("<studentId>S-1&amp;2</studentId>")
                .doesNotContain("{{studentId}}");
        assertThat(response.body().get("percentage").asString()).isEqualTo("87");
        assertThat(response.body().get("board").asString()).isEqualTo("CBSE");
        assertThat(response.sizeBytes()).isEqualTo(RESPONSE.length());
    }

    @Test
    void responseWithExternalEntityIsRejected() {
        responseBody = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<root>&xxe;</root>";
        assertThatThrownBy(() -> adapter().execute(request(TEMPLATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untrusted XML rejected");
    }

    @Test
    void departmentServerErrorSurfaces() {
        status = 500;
        assertThatThrownBy(() -> adapter().execute(request(TEMPLATE))).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void mockHostStillServedInProcessWithoutNetwork() {
        AdapterRequest mock = new AdapterRequest("BOARD_MARKS", "SOAP", MockDepartmentBackend.HOST, "/x", TEMPLATE,
                Map.of("studentId", "1"), null);
        AdapterResponse response = adapter().execute(mock);
        assertThat(response.body().get("percentage").asString()).isEqualTo("81");
        assertThat(receivedMethod.get()).isNull();
    }
}
