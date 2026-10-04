package in.samanvay.departments.revenue;

import in.samanvay.departments.kit.JourneyCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Revenue's published Samanvay manifest (v2). Capability metadata only: no secrets, no citizen values.
 * v1 fields (documents/inputs/fields/journeys) stay as the middle layer already parses them; the extra
 * blocks (lookup, auth, identity) are the v2 additions (docs/FINAL-CHANGES.md sections 10-13).
 */
@RestController
class RevenueManifestController {

    private static final Auth API_KEY = new Auth("API_KEY", List.of(new AuthParam(ApiKeyFilter.HEADER, "header", true)),
            "Send the shared API key Revenue issued to Samanvay in the X-Api-Key header.");

    private final String sftpHost;
    private final int sftpPort;
    private final String sftpHostKey;
    private final String publicBaseUrl;
    private final JourneyCatalog journeys;

    RevenueManifestController(
            @Value("${revenue.public-base-url}") String publicBaseUrl,
            @Value("${revenue.sftp.host}") String sftpHost,
            @Value("${revenue.sftp.port}") int sftpPort,
            @Value("${revenue.sftp.host-key-sha256:}") String sftpHostKey,
            JourneyCatalog journeys) {
        this.publicBaseUrl = publicBaseUrl;
        this.sftpHost = sftpHost;
        this.sftpPort = sftpPort;
        this.sftpHostKey = sftpHostKey;
        this.journeys = journeys;
    }

    @GetMapping(path = "/.well-known/samanvay/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    Manifest manifest() {
        return new Manifest(
                2,
                new Dept("REVENUE", "Revenue Department", "Issues income, caste and domicile certificates. Fake data only."),
                new Identity(AssertionSigner.PERSON_ID_TYPE, publicBaseUrl + "/login", publicBaseUrl + "/.well-known/jwks.json", AssertionSigner.ISSUER),
                new Sample("RV-1001"),
                List.of(
                        doc("INCOME_CERTIFICATE", "Income certificate", "/v1/income/{key}"),
                        doc("CASTE_CERTIFICATE", "Caste certificate", "/v1/caste/{key}"),
                        doc("DOMICILE_CERTIFICATE", "Domicile certificate", "/v1/domicile/{key}"),
                        landRecord()),
                journeys.manifestJourneys(publicBaseUrl));
    }

    /** 7/12 extract: a batch CSV on Revenue's SFTP server, one row per person, keyed by personId. */
    private Document landRecord() {
        List<String> columns = List.of("personId", "surveyNo", "village", "taluka", "district", "areaHectares", "ownerName");
        Sftp sftp = new Sftp(sftpHost, sftpPort, "/outbound", "712.csv", "CSV", "personId", columns,
                sftpHostKey == null || sftpHostKey.isBlank() ? null : sftpHostKey);
        return new Document("LAND_PARCEL", "7/12 land record extract", "SFTP_CSV", "GET", "/outbound/712.csv",
                List.of(new Input("personId", "column", true, "The citizen's Revenue person ID (the CSV key column).")),
                List.of(new Field("surveyNo", "string", false), new Field("village", "string", false),
                        new Field("taluka", "string", false), new Field("district", "string", false),
                        new Field("areaHectares", "number", false), new Field("ownerName", "string", true)),
                null, new Auth("PASSWORD", List.of(new AuthParam("username", "sftp", false), new AuthParam("password", "sftp", true)),
                        "SFTP login Revenue issued to Samanvay; pin the published host key."),
                new Access(sftp));
    }

    /**
     * Resolve: person ID in, that person's certificate keys of one type out. The manifest says how to ask (the
     * {@code type} query) and what the answer looks like, so onboarding needs no guessing.
     */
    private static Resolve resolve(String category) {
        return new Resolve("GET", "/v1/persons/{personId}/documents", Map.of("type", category), "documents", "key", "latest");
    }

    /**
     * The fields each certificate type declares: the ONE list the manifest publishes and {@link RevenueController} answers with, so a
     * column added to the database later is never sent to Samanvay until it is declared here.
     */
    static final Map<String, List<Field>> DECLARED = Map.of(
            "INCOME_CERTIFICATE", List.of(new Field("annualIncome", "integer", true), new Field("annualIncomeDisplay", "string", true),
                    new Field("holderName", "string", true), new Field("district", "string", false),
                    new Field("issuerOffice", "string", false), new Field("financialYear", "string", false)),
            "CASTE_CERTIFICATE", List.of(new Field("holderName", "string", true), new Field("caste", "string", true),
                    new Field("casteCategory", "string", true), new Field("issuerOffice", "string", false)),
            "DOMICILE_CERTIFICATE", List.of(new Field("holderName", "string", true), new Field("state", "string", false),
                    new Field("district", "string", false), new Field("issuerOffice", "string", false)));

    private static Document doc(String category, String title, String path) {
        return new Document(category, title, "REST", "GET", path,
                List.of(new Input("key", "path", true, "The certificate key returned by the resolve step.")),
                DECLARED.get(category), new Lookup(resolve(category)), API_KEY, null);
    }

    record Manifest(int manifestVersion, Dept department, Identity identity, Sample sample, List<Document> documents, List<Map<String, Object>> journeys) {}

    /** A FAKE person this department can answer for, so an admin can run a trial fetch at onboarding. Never a real person. */
    record Sample(String personId) {}

    record Dept(String code, String name, String description) {}

    /** The ID type Revenue's login will return for a person (login assertion comes in a later slice). */
    record Identity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}

    record Document(String category, String title, String protocol, String method, String path,
                    List<Input> inputs, List<Field> fields, Lookup lookup, Auth auth, Access access) {}

    /** Protocol-specific, non-secret details of where and how a document is read. */
    record Access(Sftp sftp) {}

    record Sftp(String host, int port, String directory, String fileNamePattern, String format, String keyColumn,
                List<String> columns, String hostKeyFingerprint) {}

    record Input(String name, String in, boolean required, String description) {}

    record Field(String name, String type, boolean sensitive) {}

    /** Present = the document key is not the person ID; call resolve first. */
    record Lookup(Resolve resolve) {}

    record Resolve(String method, String path, Map<String, String> query, String listField, String keyField, String latestField) {}

    /** How to authenticate: the scheme and every parameter required, never a value. */
    record Auth(String scheme, List<AuthParam> parameters, String docs) {}

    record AuthParam(String name, String in, boolean secret) {}
}
