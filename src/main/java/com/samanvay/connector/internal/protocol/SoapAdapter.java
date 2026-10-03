package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.source.AuthSpec;
import com.samanvay.connector.internal.source.SourceCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
class SoapAdapter implements ProtocolAdapter {

    private final DocumentBuilderFactory dbf;
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";

    private final MockDepartmentBackend mocks;
    private final DeadlineHttp http;
    private final String scheme;
    private final DepartmentServiceOverrides overrides;
    private final SourceCredentials credentials;

    /** Real department calls are SOAP 1.1 over HTTPS, bounded by {@link DeadlineHttp}'s total deadline. */
    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http) {
        this(mocks, http, "https", DepartmentServiceOverrides.NONE);
    }

    /**
     * Production wiring. {@code overrides} is empty unless the dev/demo profile points a source at the
     * standalone department service; the call is still the real HTTP exchange either way.
     */
    @Autowired
    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http, DepartmentServiceOverrides overrides, SourceCredentials credentials) {
        this(mocks, http, "https", overrides, credentials);
    }

    /** Without a credential store: only sources with no declared auth scheme (NONE) can be called. */
    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http, DepartmentServiceOverrides overrides) {
        this(mocks, http, "https", overrides);
    }

    /** Test seam: lets a test point the adapter at a plain-HTTP in-JVM server. */
    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme) {
        this(mocks, http, scheme, DepartmentServiceOverrides.NONE);
    }

    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme, DepartmentServiceOverrides overrides) {
        this(mocks, http, scheme, overrides, new SourceCredentials(k -> null));
    }

    SoapAdapter(MockDepartmentBackend mocks, DeadlineHttp http, String scheme, DepartmentServiceOverrides overrides,
            SourceCredentials credentials) {
        this.mocks = mocks;
        this.http = http;
        this.scheme = scheme;
        this.overrides = overrides;
        this.credentials = credentials;
        dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String protocol() {
        return "SOAP";
    }

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        String envelope = renderTemplate(request.template() == null ? "<id>{{studentId}}</id>" : request.template(), request.boundInputs());
        String raw;
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            raw = mocks.soapMarks();
        } else {
            envelope = withAuth(request, envelope);
            String endpoint = request.endpoint() == null ? "" : request.endpoint();
            String origin = overrides.baseUrl(request.dataSourceCode()).orElse(scheme + "://" + request.host());
            raw = http.post(URI.create(origin + endpoint), envelope, CONTENT_TYPE, soapActionHeader(request));
            if (raw == null) {
                raw = "";
            }
        }
        Document doc = parseSafely(raw.getBytes(StandardCharsets.UTF_8));
        return new AdapterResponse(xmlToJson(doc), raw.length());
    }

    /** The SOAPAction header a connector declares ({@code soap_action}), quoted as SOAP 1.1 writes it; none when not declared. */
    private static Map<String, String> soapActionHeader(AdapterRequest request) {
        String action = request.access().get("soap_action");
        if (action == null || action.isBlank()) {
            return Map.of();
        }
        if (action.chars().anyMatch(c -> c < 0x20 || c == '"' || c == 0x7f)) {
            throw new IllegalConnectorConfigurationException("SOAP source '" + request.dataSourceCode() + "' declares a soap_action with an illegal character");
        }
        return Map.of("SOAPAction", "\"" + action.trim() + "\"");
    }

    /** The envelope with the department's declared WS-Security header added; unchanged when no scheme is declared. */
    private String withAuth(AdapterRequest request, String envelope) {
        AuthSpec spec = AuthSpec.of(request.authType(), request.authSpecJson());
        if (spec.isNone()) {
            return envelope;
        }
        String source = request.dataSourceCode();
        if (!"WS_SECURITY_USERNAME".equals(spec.scheme())) {
            throw new IllegalConnectorConfigurationException(
                    "SOAP source '" + source + "' has an unsupported auth scheme '" + spec.scheme() + "'");
        }
        String code = SourceCredentials.credentialCode(source, request.authConfigRef());
        Map<String, String> secret = credentials.params(code);
        return SoapSecurity.addUsernameToken(envelope, SourceCredentials.requireParam(source, code, secret, "username"),
                SourceCredentials.requireParam(source, code, secret, "password"));
    }

    String renderTemplate(String template, Map<String, String> inputs) {
        String rendered = template;
        for (var e : inputs.entrySet()) {
            rendered = rendered.replace("{{" + e.getKey() + "}}", escapeXml(e.getValue()));
        }
        return rendered;
    }

    Document parseSafely(byte[] raw) {
        try {
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("untrusted XML rejected", e);
        }
    }

    private static ObjectNode xmlToJson(Document doc) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        flatten(doc.getDocumentElement(), n);
        return n;
    }

    private static void flatten(Node node, ObjectNode out) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                if (c.getChildNodes().getLength() == 1 && c.getFirstChild().getNodeType() == Node.TEXT_NODE) {
                    out.put(c.getLocalName() == null ? c.getNodeName() : c.getLocalName(), c.getTextContent());
                } else {
                    flatten(c, out);
                }
            }
        }
    }

    static String escapeXml(String v) {
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
