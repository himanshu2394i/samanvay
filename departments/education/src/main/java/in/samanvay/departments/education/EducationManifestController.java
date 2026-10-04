package in.samanvay.departments.education;

import in.samanvay.departments.kit.JourneyCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Education's published Samanvay manifest (v2). Capability metadata only: no secrets, no citizen values.
 * One student ID unlocks the marks, so the document publishes no {@code lookup.resolve}.
 */
@RestController
class EducationManifestController {

    private final String publicBaseUrl;
    private final JourneyCatalog journeys;

    EducationManifestController(@Value("${education.public-base-url}") String publicBaseUrl, JourneyCatalog journeys) {
        this.publicBaseUrl = publicBaseUrl;
        this.journeys = journeys;
    }

    /** Full request envelope minus the security header: Samanvay inserts the WS-Security header into soap:Header. */
    static final String REQUEST_TEMPLATE = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Header/>"
            + "<soap:Body><GetMarksRequest><studentId>{{studentId}}</studentId></GetMarksRequest></soap:Body></soap:Envelope>";

    @GetMapping(path = "/.well-known/samanvay/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    Manifest manifest() {
        Auth wss = new Auth("WS_SECURITY_USERNAME", "PasswordText",
                List.of(new AuthParam("username", "soap-header", false), new AuthParam("password", "soap-header", true)),
                "WS-Security UsernameToken Education issued to Samanvay; Samanvay adds it to the SOAP header.");
        Document marks = new Document("MARKS", "HSC marks statement", "SOAP", "POST", "/marks/service",
                List.of(new Input("studentId", "body", true, "The citizen's student ID (the person ID returned at Education login).")),
                List.of(new Field("percentage", "number", false), new Field("board", "string", false), new Field("exam", "string", false)),
                wss, new Access(new Soap("/marks/service", "GetMarks", REQUEST_TEMPLATE, "text/xml")));
        return new Manifest(2,
                new Dept("EDUCATION", "State Board of Education", "Holds board examination marks. Fake data only."),
                new Identity(AssertionSigner.PERSON_ID_TYPE, publicBaseUrl + "/login", publicBaseUrl + "/.well-known/jwks.json", AssertionSigner.ISSUER), List.of(marks),
                new Sample("EDU-1001"),
                journeys.manifestJourneys(publicBaseUrl));
    }

    record Manifest(int manifestVersion, Dept department, Identity identity, List<Document> documents, Sample sample, List<Map<String, Object>> journeys) {}

    /** A FAKE person this department can answer for, so an admin can run a trial fetch at onboarding. Never a real person. */
    record Sample(String personId) {}

    record Dept(String code, String name, String description) {}

    record Identity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}

    record Document(String category, String title, String protocol, String method, String path,
                    List<Input> inputs, List<Field> fields, Auth auth, Access access) {}

    record Input(String name, String in, boolean required, String description) {}

    record Field(String name, String type, boolean sensitive) {}

    record Access(Soap soap) {}

    record Soap(String endpoint, String soapAction, String requestTemplate, String contentType) {}

    /** How to authenticate: the scheme and every parameter required, never a value. */
    record Auth(String scheme, String passwordType, List<AuthParam> parameters, String docs) {}

    record AuthParam(String name, String in, boolean secret) {}

}
