package in.samanvay.simulators.department;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The standardized Samanvay discovery manifest.
 *
 * <p>A department platform publishes, at a well-known path, the (non-sensitive) metadata about
 * the documents it holds and the journeys it offers, so the middle layer can onboard it from just
 * a base URL — no hand-typing of categories, endpoints or field structure. This is the minimal,
 * standardized contract a real department adds on its side; everything else (mapping, connectors,
 * consent, tracking) stays in the middle layer.
 *
 * <pre>GET /.well-known/samanvay/manifest  ->  application/json</pre>
 *
 * The middle layer maps each {@code documents[]} entry to a data source + connector (category,
 * protocol, endpoint, inputs) and its {@code fields[]} to the output schema / suggested mapping;
 * {@code journeys[]} to services with their required document categories. {@code sensitive} marks
 * fields the department considers personal data (the payload values themselves are never in the
 * manifest — this is capability metadata only).
 */
@RestController
class SamanvayManifestController {

    @GetMapping(path = "/.well-known/samanvay/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    Manifest manifest() {
        return new Manifest(
                1,
                new Dept("SANDBOX", "Sandbox Department", "Reference department that publishes its documents for Samanvay discovery. Fake data only."),
                List.of(
                        new Document("INCOME_CERTIFICATE", "Income certificate", "REST", "GET", "/v1/income",
                                List.of(new Input("rationCard", "query", true, "The citizen's ration card number.")),
                                List.of(new Field("annualIncome", "integer", true), new Field("annualIncomeDisplay", "string", true),
                                        new Field("holderName", "string", true), new Field("district", "string", false),
                                        new Field("issuerOffice", "string", false))),
                        new Document("BANK_ACCOUNT", "Bank account", "REST", "GET", "/bank",
                                List.of(new Input("dbtId", "query", true, "The citizen's DBT identifier.")),
                                List.of(new Field("accountRef", "string", true), new Field("ifscMasked", "string", false),
                                        new Field("holderName", "string", true))),
                        new Document("MARKS", "Marks statement", "SOAP", "POST", "/marks/service",
                                List.of(new Input("studentId", "body", true, "The citizen's student identifier.")),
                                List.of(new Field("percentage", "number", false), new Field("board", "string", false),
                                        new Field("exam", "string", false)))),
                List.of(
                        new Journey("SANDBOX_SUBSIDY", "Sandbox subsidy", "A sample service this department offers.",
                                List.of("INCOME_CERTIFICATE", "BANK_ACCOUNT"))));
    }

    record Manifest(int manifestVersion, Dept department, List<Document> documents, List<Journey> journeys) {}

    record Dept(String code, String name, String description) {}

    record Document(String category, String title, String protocol, String method, String path,
                    List<Input> inputs, List<Field> fields) {}

    record Input(String name, String in, boolean required, String description) {}

    record Field(String name, String type, boolean sensitive) {}

    record Journey(String code, String name, String description, List<String> requiredCategories) {}
}
