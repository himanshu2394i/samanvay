package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** SOAP calls carry the WS-Security UsernameToken the department's manifest declared, in the SOAP header. */
class SoapAdapterAuthTest {

    static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String ENV_OPEN = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">";
    static final String BODY = "<soap:Body><GetMarksRequest><studentId>{{studentId}}</studentId></GetMarksRequest></soap:Body>";
    static final String SPEC = "{\"scheme\":\"WS_SECURITY_USERNAME\",\"passwordType\":\"PasswordText\","
            + "\"parameters\":[{\"name\":\"username\",\"in\":\"soap-header\"},{\"name\":\"password\",\"in\":\"soap-header\",\"secret\":true}]}";

    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><R><percentage>91</percentage></R></soap:Body></soap:Envelope>")
                    .getBytes(StandardCharsets.UTF_8);
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

    SoapAdapter adapter(Map<String, String> secrets) {
        SecretStore store = k -> secrets.containsKey(k) ? new SecretStore.Secret(secrets.get(k).getBytes(StandardCharsets.UTF_8)) : null;
        DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new SoapAdapter(new MockDepartmentBackend(), http, "http", DepartmentServiceOverrides.NONE, new SourceCredentials(store));
    }

    AdapterRequest req(String template, String authType, String spec) {
        return new AdapterRequest("edu", "SOAP", "127.0.0.1:" + server.getAddress().getPort(), "/marks/service", template,
                Map.of("studentId", "EDU-1001"), "secret:edu", authType, spec);
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static String text(Document d, String local) {
        return d.getElementsByTagNameNS("*", local).item(0).getTextContent();
    }

    @Test
    void an_empty_header_gets_the_username_token() throws Exception {
        adapter(Map.of("source-edu-credential", "{\"username\":\"samanvay-dev\",\"password\":\"pw\"}"))
                .execute(req(ENV_OPEN + "<soap:Header/>" + BODY + "</soap:Envelope>", "WS_SECURITY_USERNAME", SPEC));
        Document d = parse(bodies.get(0));
        assertThat(text(d, "Username")).isEqualTo("samanvay-dev");
        assertThat(text(d, "Password")).isEqualTo("pw");
        assertThat(d.getElementsByTagNameNS(WSSE, "Security").getLength()).isEqualTo(1);
        assertThat(d.getElementsByTagNameNS("*", "Security").item(0).getParentNode().getLocalName()).isEqualTo("Header");
        assertThat(text(d, "studentId")).isEqualTo("EDU-1001");
        assertThat(bodies.get(0)).contains("#PasswordText");
    }

    @Test
    void an_existing_header_keeps_its_content_and_gains_the_token() throws Exception {
        adapter(Map.of("source-edu-credential", "{\"username\":\"u\",\"password\":\"p\"}"))
                .execute(req(ENV_OPEN + "<soap:Header><x:Trace xmlns:x=\"urn:x\">t-1</x:Trace></soap:Header>" + BODY + "</soap:Envelope>",
                        "WS_SECURITY_USERNAME", SPEC));
        Document d = parse(bodies.get(0));
        assertThat(text(d, "Trace")).isEqualTo("t-1");
        assertThat(text(d, "Username")).isEqualTo("u");
        assertThat(d.getElementsByTagNameNS("*", "Header").getLength()).isEqualTo(1);
    }

    @Test
    void an_envelope_without_a_header_gets_one_before_the_body() throws Exception {
        adapter(Map.of("source-edu-credential", "{\"username\":\"u\",\"password\":\"p\"}"))
                .execute(req(ENV_OPEN + BODY + "</soap:Envelope>", "WS_SECURITY_USERNAME", SPEC));
        String sent = bodies.get(0);
        assertThat(sent.indexOf("Header")).isLessThan(sent.indexOf("Body"));
        assertThat(text(parse(sent), "Username")).isEqualTo("u");
    }

    @Test
    void xml_special_characters_in_the_credentials_are_escaped_not_injected() throws Exception {
        adapter(Map.of("source-edu-credential", "{\"username\":\"a<b\",\"password\":\"p&q\\\"'</wsse:Password><evil/>\"}"))
                .execute(req(ENV_OPEN + "<soap:Header/>" + BODY + "</soap:Envelope>", "WS_SECURITY_USERNAME", SPEC));
        Document d = parse(bodies.get(0));
        assertThat(text(d, "Username")).isEqualTo("a<b");
        assertThat(text(d, "Password")).isEqualTo("p&q\"'</wsse:Password><evil/>");
        assertThat(d.getElementsByTagNameNS("*", "evil").getLength()).isZero();
    }

    @Test
    void no_auth_scheme_leaves_the_envelope_untouched() {
        adapter(Map.of()).execute(req(ENV_OPEN + "<soap:Header/>" + BODY + "</soap:Envelope>", "NONE", null));
        assertThat(bodies.get(0)).doesNotContain("Security").contains("<soap:Header/>");
    }

    @Test
    void a_missing_credential_names_source_and_parameter_and_makes_no_call() {
        assertThatThrownBy(() -> adapter(Map.of()).execute(req(ENV_OPEN + "<soap:Header/>" + BODY + "</soap:Envelope>", "WS_SECURITY_USERNAME", SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("edu").hasMessageContaining("username");
        assertThat(bodies).isEmpty();
    }

    @Test
    void a_template_that_is_not_a_full_envelope_cannot_carry_a_security_header() {
        assertThatThrownBy(() -> adapter(Map.of("source-edu-credential", "{\"username\":\"u\",\"password\":\"p\"}"))
                .execute(req("<id>{{studentId}}</id>", "WS_SECURITY_USERNAME", SPEC)))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("envelope");
        assertThat(bodies).isEmpty();
    }

    @Test
    void an_unsupported_scheme_for_soap_is_refused() {
        assertThatThrownBy(() -> adapter(Map.of()).execute(req(ENV_OPEN + BODY + "</soap:Envelope>", "OAUTH2_CLIENT", "{\"scheme\":\"OAUTH2_CLIENT\"}")))
                .isInstanceOf(IllegalConnectorConfigurationException.class).hasMessageContaining("unsupported");
        assertThat(bodies).isEmpty();
    }
}
