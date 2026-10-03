package in.samanvay.departments.education;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Education over real HTTP: a SOAP 1.1 marks service guarded by a WS-Security UsernameToken, plus its manifest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EducationDepartmentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String USER = "samanvay-dev";
    static final String PASS = "education-dev-secret-change-me";
    static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    @LocalServerPort
    int port;

    static String envelope(String securityHeader, String studentId) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Header>" + securityHeader
                + "</soap:Header><soap:Body><GetMarksRequest><studentId>" + studentId + "</studentId></GetMarksRequest></soap:Body></soap:Envelope>";
    }

    static String token(String user, String pass) {
        return "<wsse:Security xmlns:wsse=\"" + WSSE + "\"><wsse:UsernameToken><wsse:Username>" + user
                + "</wsse:Username><wsse:Password>" + pass + "</wsse:Password></wsse:UsernameToken></wsse:Security>";
    }

    HttpResponse<String> soap(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/marks/service"))
                .header("Content-Type", "text/xml; charset=utf-8").header("SOAPAction", "GetMarks")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void manifest_is_public_and_declares_soap_access_and_ws_security() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/samanvay/manifest")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode m = JSON.readTree(r.body());
        assertThat(m.get("department").get("code").asString()).isEqualTo("EDUCATION");
        assertThat(m.get("identity").get("personIdType").asString()).isEqualTo("EDU_STUDENT_ID");
        JsonNode marks = m.get("documents").get(0);
        assertThat(marks.get("category").asString()).isEqualTo("MARKS");
        assertThat(marks.get("protocol").asString()).isEqualTo("SOAP");
        assertThat(marks.has("lookup")).isFalse();
        JsonNode soap = marks.get("access").get("soap");
        assertThat(soap.get("endpoint").asString()).isEqualTo("/marks/service");
        assertThat(soap.get("soapAction").asString()).isEqualTo("GetMarks");
        assertThat(soap.get("requestTemplate").asString()).contains("{{studentId}}").contains("<soap:Header");
        JsonNode auth = marks.get("auth");
        assertThat(auth.get("scheme").asString()).isEqualTo("WS_SECURITY_USERNAME");
        assertThat(auth.get("passwordType").asString()).isEqualTo("PasswordText");
        assertThat(auth.toString()).doesNotContain(PASS);
    }

    @Test
    void manifest_publishes_the_scholarship_journey_needing_documents_from_three_departments() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/samanvay/manifest")).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = JSON.readTree(r.body()).get("journeys");
        assertThat(j).hasSize(1);
        assertThat(j.get(0).get("code").asString()).isEqualTo("POST_MATRIC_SCHOLARSHIP");
        assertThat(j.get(0).get("referencePrefix").asString()).isEqualTo("PMS");
        assertThat(j.get(0).get("consentPurpose").asString()).isEqualTo("SCHOLARSHIP_ELIGIBILITY");
        assertThat(j.get(0).get("requester").asString()).isEqualTo("EDUCATION");
        java.util.Set<String> needs = new java.util.TreeSet<>();
        j.get(0).get("requiredCategories").forEach(c -> needs.add(c.get("department").asString() + ":" + c.get("category").asString()));
        assertThat(needs).containsExactly("DBT:BANK_ACCOUNT", "EDUCATION:MARKS", "REVENUE:CASTE_CERTIFICATE", "REVENUE:INCOME_CERTIFICATE");
    }

    HttpResponse<String> soapWithAction(String body, String action) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/marks/service"))
                .header("Content-Type", "text/xml; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body));
        if (action != null) {
            b.header("SOAPAction", action);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_soap_action_the_manifest_declares_is_required_and_checked() throws Exception {
        String ok = envelope(token(USER, PASS), "EDU-1001");
        assertThat(soapWithAction(ok, "GetMarks").statusCode()).isEqualTo(200);
        assertThat(soapWithAction(ok, "\"GetMarks\"").statusCode()).isEqualTo(200); // SOAP 1.1 allows it quoted
        for (String bad : new String[] {null, "", "GetSomethingElse", "\"\""}) {
            HttpResponse<String> r = soapWithAction(ok, bad);
            assertThat(r.statusCode()).as("SOAPAction=" + bad).isEqualTo(500);
            assertThat(r.body()).contains("soap:Client").contains("SOAPAction").doesNotContain("percentage");
        }
    }

    @Test
    void manifest_publishes_a_sample_person_for_trial_fetches() throws Exception {
        JsonNode m = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/samanvay/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(m.get("sample").get("personId").asString()).isEqualTo("EDU-1001");
    }

    @Test
    void a_valid_username_token_gets_the_marks() throws Exception {
        HttpResponse<String> r = soap(envelope(token(USER, PASS), "EDU-1001"));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("<GetMarksResponse>").contains("<percentage>91</percentage>")
                .contains("<board>msbshse</board>").contains("<exam>HSC 2025</exam>");
    }

    @Test
    void a_missing_or_wrong_token_is_a_soap_fault() throws Exception {
        for (String header : new String[] {"", token(USER, "wrong"), token("other", PASS)}) {
            HttpResponse<String> r = soap(envelope(header, "EDU-1001"));
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(r.body()).contains("<soap:Fault>").contains("wsse:FailedAuthentication").doesNotContain("percentage");
        }
    }

    @Test
    void an_unknown_student_is_a_client_fault_after_authentication() throws Exception {
        HttpResponse<String> r = soap(envelope(token(USER, PASS), "EDU-9999"));
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("soap:Client").contains("no marks for this student");
    }

    @Test
    void untrusted_xml_with_a_doctype_is_rejected_not_expanded() throws Exception {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + envelope(token(USER, PASS), "&e;");
        HttpResponse<String> r = soap(evil);
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("soap:Client").doesNotContain("root:");
    }
}
