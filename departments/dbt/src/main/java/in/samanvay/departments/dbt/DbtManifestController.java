package in.samanvay.departments.dbt;

import in.samanvay.departments.kit.JourneyCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DBT's published Samanvay manifest (v2). Capability metadata only: no secrets, no citizen values.
 * One DBT ID unlocks the bank record, so the document publishes no {@code lookup.resolve}.
 */
@RestController
class DbtManifestController {

    private final TokenService tokens;
    private final String publicBaseUrl;
    private final JourneyCatalog journeys;

    DbtManifestController(TokenService tokens, @Value("${dbt.public-base-url}") String publicBaseUrl, JourneyCatalog journeys) {
        this.tokens = tokens;
        this.publicBaseUrl = publicBaseUrl;
        this.journeys = journeys;
    }

    @GetMapping(path = "/.well-known/samanvay/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    Manifest manifest() {
        Auth oauth = new Auth("OAUTH2_CLIENT", "/oauth/token", List.of(tokens.scope()),
                List.of(new AuthParam("client_id", "token-request", false), new AuthParam("client_secret", "token-request", true)),
                "Samanvay's OAuth2 client for DBT; exchange it at the token URL, then send the token as a Bearer header.");
        Document bank = new Document("BANK_ACCOUNT", "Bank account for DBT", "REST", "POST", "/v1/bank",
                List.of(new Input("dbtId", "body", true, "The citizen's DBT ID (the person ID returned at DBT login).")),
                List.of(new Field("accountRef", "string", true), new Field("ifscMasked", "string", false),
                        new Field("holderName", "string", true)),
                oauth);
        return new Manifest(2,
                new Dept("DBT", "Direct Benefit Transfer", "Holds each person's bank account for benefit payments. Fake data only."),
                new Identity(AssertionSigner.PERSON_ID_TYPE, publicBaseUrl + "/login", publicBaseUrl + "/.well-known/jwks.json", AssertionSigner.ISSUER), List.of(bank),
                new Sample("DBT-1001"),
                journeys.manifestJourneys(publicBaseUrl));
    }

    record Manifest(int manifestVersion, Dept department, Identity identity, List<Document> documents, Sample sample, List<Map<String, Object>> journeys) {}

    /** A FAKE person this department can answer for, so an admin can run a trial fetch at onboarding. Never a real person. */
    record Sample(String personId) {}

    record Dept(String code, String name, String description) {}

    record Identity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}

    record Document(String category, String title, String protocol, String method, String path,
                    List<Input> inputs, List<Field> fields, Auth auth) {}

    record Input(String name, String in, boolean required, String description) {}

    record Field(String name, String type, boolean sensitive) {}

    /** How to authenticate: the scheme and every parameter required, never a value. */
    record Auth(String scheme, String tokenUrl, List<String> scopes, List<AuthParam> parameters, String docs) {}

    record AuthParam(String name, String in, boolean secret) {}
}
