package in.samanvay.departments.education;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Education's SOAP 1.1 face: {@code POST /marks/service}. The request must carry a WS-Security
 * UsernameToken (PasswordText) in the SOAP header; a missing or wrong token, like any other failure, is a
 * SOAP Fault with HTTP 500, which is how a real SOAP service reports errors.
 *
 * <p>ponytail: PasswordText only (assumes TLS in front); PasswordDigest + nonce/timestamp when a real
 * department requires it. One shared username, constant-time compare.
 */
@RestController
class EducationController {

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    record Marks(String studentId, String percentage, String board, String exam) {}

    private static final Map<String, Marks> MARKS = Map.of(
            "EDU-1001", new Marks("EDU-1001", "91", "msbshse", "HSC 2025"),
            "EDU-1002", new Marks("EDU-1002", "81", "msbshse", "HSC 2025"));

    private final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    private final byte[] username;
    private final byte[] password;

    EducationController(@Value("${education.wss.username}") String username, @Value("${education.wss.password}") String password)
            throws Exception {
        this.username = username.getBytes(StandardCharsets.UTF_8);
        this.password = password.getBytes(StandardCharsets.UTF_8);
        // The request is untrusted XML: no DOCTYPE, no external entities.
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
    }

    @PostMapping(path = "/marks/service", consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE})
    ResponseEntity<String> marks(@RequestBody String envelope, @RequestHeader(name = "SOAPAction", required = false) String soapAction) {
        // The action the manifest declares (access.soap.soapAction); SOAP 1.1 allows it quoted.
        String action = soapAction == null ? "" : soapAction.trim().replace("\"", "");
        if (!"GetMarks".equals(action)) {
            return fault("soap:Client", "SOAPAction must be GetMarks");
        }
        Document doc;
        try {
            doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return fault("soap:Client", "Malformed SOAP request");
        }
        if (!authenticated(doc)) {
            return fault("wsse:FailedAuthentication", "Missing or invalid WS-Security UsernameToken");
        }
        String studentId = text(doc, "studentId");
        if (studentId == null || studentId.isBlank()) {
            return fault("soap:Client", "studentId is required");
        }
        Marks marks = MARKS.get(studentId.trim());
        if (marks == null) {
            return fault("soap:Client", "no marks for this student");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Body><GetMarksResponse>"
                + "<studentId>" + escape(marks.studentId()) + "</studentId>"
                + "<percentage>" + marks.percentage() + "</percentage>"
                + "<board>" + marks.board() + "</board>"
                + "<exam>" + marks.exam() + "</exam>"
                + "</GetMarksResponse></soap:Body></soap:Envelope>";
        return ResponseEntity.ok().contentType(xmlUtf8()).body(xml);
    }

    private boolean authenticated(Document doc) {
        String user = text(doc, "Username");
        String pass = text(doc, "Password");
        if (user == null || pass == null) {
            return false;
        }
        // Evaluate both so a wrong username and a wrong password cost the same.
        boolean userOk = MessageDigest.isEqual(username, user.trim().getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(password, pass.trim().getBytes(StandardCharsets.UTF_8));
        return userOk & passOk;
    }

    private static String text(Document doc, String localName) {
        NodeList found = doc.getElementsByTagNameNS("*", localName);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    private static ResponseEntity<String> fault(String code, String reason) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Body><soap:Fault>"
                + "<faultcode>" + code + "</faultcode><faultstring>" + escape(reason) + "</faultstring>"
                + "</soap:Fault></soap:Body></soap:Envelope>";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(xmlUtf8()).body(xml);
    }

    private static MediaType xmlUtf8() {
        return new MediaType("text", "xml", StandardCharsets.UTF_8);
    }

    private static String escape(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
