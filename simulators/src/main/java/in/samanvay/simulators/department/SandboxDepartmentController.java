package in.samanvay.simulators.department;

import in.samanvay.simulators.department.SandboxDepartmentData.Bank;
import in.samanvay.simulators.department.SandboxDepartmentData.Income;
import in.samanvay.simulators.department.SandboxDepartmentData.Marks;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The sandbox department: the network endpoints behind the catalog sources seeded by V193 in the main
 * app ({@code sandbox-income-rest}, {@code sandbox-marks-soap}).
 *
 * <ul>
 *   <li>{@code GET /v1/income?rationCard=...}: JSON {@code annualIncome, holderName, ...} (REST).
 *   <li>{@code POST /marks/service}: SOAP 1.1 ({@code text/xml}); the body carries
 *       {@code <studentId>}, the answer is a {@code GetMarksResponse} with
 *       {@code percentage, board, exam} (SOAP).
 * </ul>
 *
 * Fake data only. Faults follow each protocol's convention: REST answers 400 with a small JSON error,
 * SOAP answers 500 with a SOAP 1.1 {@code Fault} (which is how a real SOAP service reports a client error).
 */
@RestController
class SandboxDepartmentController {

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

    SandboxDepartmentController() throws Exception {
        // The request is untrusted XML: no DOCTYPE, no external entities.
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
    }

    @GetMapping(path = "/v1/income", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> income(@RequestParam(name = "rationCard", required = false) String rationCard) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rationCard == null || rationCard.isBlank()) {
            body.put("error", "rationCard is required");
            body.put("samanvay_simulator", true);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Income income = SandboxDepartmentData.income(rationCard.trim());
        body.put("annualIncome", income.annualIncome());
        body.put("annualIncomeDisplay", income.annualIncomeDisplay());
        body.put("holderName", income.holderName());
        body.put("district", income.district());
        body.put("issuerOffice", income.issuerOffice());
        body.put("samanvay_simulator", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping(path = "/bank", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> bank(@RequestParam(name = "dbtId", required = false) String dbtId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (dbtId == null || dbtId.isBlank()) {
            body.put("error", "dbtId is required");
            body.put("samanvay_simulator", true);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Bank bank = SandboxDepartmentData.bank(dbtId.trim());
        body.put("accountRef", bank.accountRef());
        body.put("ifscMasked", bank.ifscMasked());
        body.put("holderName", bank.holderName());
        body.put("samanvay_simulator", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping(path = "/marks/service", consumes = MediaType.TEXT_XML_VALUE, produces = MediaType.TEXT_XML_VALUE)
    ResponseEntity<String> marks(@RequestBody String envelope) {
        String studentId;
        try {
            studentId = firstText(envelope, "studentId");
        } catch (Exception e) {
            return fault("soap:Client", "Malformed SOAP request");
        }
        if (studentId == null || studentId.isBlank()) {
            return fault("soap:Client", "studentId is required");
        }
        Marks marks = SandboxDepartmentData.marks(studentId.trim());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Body><GetMarksResponse>"
                + "<studentId>" + escape(marks.studentId()) + "</studentId>"
                + "<percentage>" + marks.percentage() + "</percentage>"
                + "<board>" + marks.board() + "</board>"
                + "<exam>" + marks.exam() + "</exam>"
                + "</GetMarksResponse></soap:Body></soap:Envelope>";
        return ResponseEntity.ok().contentType(xmlUtf8()).body(xml);
    }

    private String firstText(String xml, String localName) throws Exception {
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
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
