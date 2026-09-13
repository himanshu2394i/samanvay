package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
class SoapAdapter implements ProtocolAdapter {

    private final DocumentBuilderFactory dbf;
    private final MockDepartmentBackend mocks;

    SoapAdapter(MockDepartmentBackend mocks) {
        this.mocks = mocks;
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
            raw = envelope;
        }
        Document doc = parseSafely(raw.getBytes(StandardCharsets.UTF_8));
        return new AdapterResponse(xmlToJson(doc), raw.length());
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
