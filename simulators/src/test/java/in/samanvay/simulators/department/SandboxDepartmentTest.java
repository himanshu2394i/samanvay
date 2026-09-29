package in.samanvay.simulators.department;

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

/** The sandbox department's own REST and SOAP behaviour, over a real socket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SandboxDepartmentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void rest_income_is_open_json_with_the_marker() throws Exception {
        HttpResponse<String> r = get("/v1/income?rationCard=RC-1001");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        assertThat(r.headers().firstValue("X-Samanvay-Simulator")).contains("true");
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("annualIncome").asString()).isEqualTo("742000");
        assertThat(body.get("holderName").asString()).isEqualTo("Sandbox Holder");
    }

    @Test
    void rest_income_is_deterministic_for_unknown_cards() throws Exception {
        String first = get("/v1/income?rationCard=RC-ZZ-42").body();
        assertThat(get("/v1/income?rationCard=RC-ZZ-42").body()).isEqualTo(first);
        assertThat(JSON.readTree(first).get("annualIncome").asString()).matches("[0-9]{6,7}");
    }

    @Test
    void rest_income_without_a_ration_card_is_400() throws Exception {
        assertThat(get("/v1/income").statusCode()).isEqualTo(400);
    }

    @Test
    void soap_marks_answers_a_soap_1_1_envelope() throws Exception {
        HttpResponse<String> r = soap(envelope("S-1001"));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/xml"));
        assertThat(r.body())
                .contains("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">")
                .contains("<studentId>S-1001</studentId>")
                .contains("<percentage>91</percentage>")
                .contains("<board>icse</board>");
    }

    @Test
    void soap_marks_escapes_the_echoed_student_id() throws Exception {
        assertThat(soap(envelope("S-1&amp;2")).body()).contains("<studentId>S-1&amp;2</studentId>");
    }

    @Test
    void soap_client_errors_are_500_faults() throws Exception {
        for (String bad : new String[] {envelope(" "), "<not-xml", "<!DOCTYPE x [<!ENTITY e \"x\">]><x><studentId>&e;</studentId></x>"}) {
            HttpResponse<String> r = soap(bad);
            assertThat(r.statusCode()).as(bad).isEqualTo(500);
            assertThat(r.body()).contains("<faultcode>soap:Client</faultcode>");
        }
    }

    static String envelope(String studentIdXml) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<GetMarks><studentId>" + studentIdXml + "</studentId></GetMarks></soap:Body></soap:Envelope>";
    }

    HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> soap(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/marks/service"))
                        .header("Content-Type", "text/xml; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
