package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.AdapterRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SOAP 1.1 services route on the SOAPAction header; a connector that declares one has it sent (quoted, as the spec writes it). */
class SoapActionTest {

    static final String ENV = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><R>{{studentId}}</R></soap:Body></soap:Envelope>";

    HttpServer server;
    final List<String> actions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String a = ex.getRequestHeaders().getFirst("SOAPAction");
            actions.add(a == null ? "<none>" : a);
            ex.getRequestBody().readAllBytes();
            byte[] out = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><R><percentage>9</percentage></R></soap:Body></soap:Envelope>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    void call(Map<String, String> access) {
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        new SoapAdapter(new MockDepartmentBackend(), http, "http").execute(new AdapterRequest("edu", "SOAP", "127.0.0.1:" + server.getAddress().getPort(),
                "/marks/service", ENV, Map.of("studentId", "S1"), "secret:none", "NONE", null, access));
    }

    @Test
    void a_declared_soap_action_is_sent_in_quotes() {
        call(Map.of("soap_action", "GetMarks"));
        assertThat(actions).containsExactly("\"GetMarks\"");
    }

    @Test
    void with_none_declared_no_soap_action_header_is_invented() {
        call(Map.of());
        assertThat(actions).containsExactly("<none>");
    }

    @Test
    void an_action_that_could_break_the_header_is_refused_before_any_call() {
        for (String bad : new String[] {"Get\r\nX-Evil: 1", "Get\"Marks", "a\nb"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call(Map.of("soap_action", bad)))
                    .as(bad).isInstanceOf(com.samanvay.connector.api.IllegalConnectorConfigurationException.class);
        }
        assertThat(actions).isEmpty();
    }
}
