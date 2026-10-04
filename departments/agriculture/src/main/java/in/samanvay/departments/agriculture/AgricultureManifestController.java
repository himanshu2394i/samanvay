package in.samanvay.departments.agriculture;

import in.samanvay.departments.kit.JourneyCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agriculture's published Samanvay manifest (v2). Capability metadata only: no secrets, no citizen values,
 * and no SQL: the JDBC document names a read-only view and its key column; Samanvay builds the fixed query.
 * One Agriculture farmer ID unlocks both documents, so neither publishes a {@code lookup.resolve}.
 */
@RestController
class AgricultureManifestController {

    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String sftpHost;
    private final int sftpPort;
    private final String sftpHostKey;
    private final String publicBaseUrl;
    private final JourneyCatalog journeys;

    AgricultureManifestController(
            @Value("${agriculture.public-base-url}") String publicBaseUrl,
            @Value("${agriculture.db.host}") String dbHost,
            @Value("${agriculture.db.port}") int dbPort,
            @Value("${agriculture.db.name}") String dbName,
            @Value("${agriculture.sftp.host}") String sftpHost,
            @Value("${agriculture.sftp.port}") int sftpPort,
            @Value("${agriculture.sftp.host-key-sha256:}") String sftpHostKey,
            JourneyCatalog journeys) {
        this.publicBaseUrl = publicBaseUrl;
        this.journeys = journeys;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.sftpHost = sftpHost;
        this.sftpPort = sftpPort;
        this.sftpHostKey = sftpHostKey;
    }

    @GetMapping(path = "/.well-known/samanvay/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    Manifest manifest() {
        return new Manifest(2,
                new Dept("AGRICULTURE", "Department of Agriculture", "Holds farmer records and crop sowing reports. Fake data only."),
                new Identity(AssertionSigner.PERSON_ID_TYPE, publicBaseUrl + "/login", publicBaseUrl + "/.well-known/jwks.json", AssertionSigner.ISSUER),
                new Sample("AG-1001"),
                List.of(farmerRecord(), cropSowing()),
                journeys.manifestJourneys(publicBaseUrl));
    }

    private Document farmerRecord() {
        Jdbc jdbc = new Jdbc(dbHost, dbPort, dbName, "v_farmer_record", "agri_person_id", true);
        return new Document("FARMER_RECORD", "Farmer record", "JDBC", null, null,
                List.of(new Input("agri_person_id", "column", true, "The farmer's Agriculture ID (the person ID returned at Agriculture login).")),
                List.of(new Field("farmer_name", "string", true), new Field("village", "string", false),
                        new Field("taluka", "string", false), new Field("land_hectares", "number", false)),
                new Auth("DB_USER", List.of(new AuthParam("username", "db", false), new AuthParam("password", "db", true)),
                        "Read-only database account Agriculture issued to Samanvay; connections must use TLS."),
                new Access(jdbc, null));
    }

    private Document cropSowing() {
        Sftp sftp = new Sftp(sftpHost, sftpPort, "/outbound", "crop.csv", "CSV", "agriPersonId",
                List.of("agriPersonId", "season", "crop", "areaHectares"),
                sftpHostKey == null || sftpHostKey.isBlank() ? null : sftpHostKey);
        return new Document("CROP_RECORD", "Crop sowing record", "SFTP_CSV", "GET", "/outbound/crop.csv",
                List.of(new Input("agriPersonId", "column", true, "The farmer's Agriculture ID (the CSV key column).")),
                List.of(new Field("season", "string", false), new Field("crop", "string", false), new Field("areaHectares", "number", false)),
                new Auth("PASSWORD", List.of(new AuthParam("username", "sftp", false), new AuthParam("password", "sftp", true)),
                        "SFTP login Agriculture issued to Samanvay; pin the published host key."),
                new Access(null, sftp));
    }

    record Manifest(int manifestVersion, Dept department, Identity identity, Sample sample, List<Document> documents, List<Map<String, Object>> journeys) {}

    /** A FAKE person this department can answer for, so an admin can run a trial fetch at onboarding. Never a real person. */
    record Sample(String personId) {}

    record Dept(String code, String name, String description) {}

    record Identity(String personIdType, String loginUrl, String jwksUrl, String assertionIssuer) {}

    record Document(String category, String title, String protocol, String method, String path,
                    List<Input> inputs, List<Field> fields, Auth auth, Access access) {}

    record Input(String name, String in, boolean required, String description) {}

    record Field(String name, String type, boolean sensitive) {}

    record Access(Jdbc jdbc, Sftp sftp) {}

    /** A read-only VIEW and its key column: Samanvay builds the one fixed SELECT; the department never sends SQL. */
    record Jdbc(String host, int port, String database, String readOnlyView, String keyColumn, boolean tlsRequired) {}

    record Sftp(String host, int port, String directory, String fileNamePattern, String format, String keyColumn,
                List<String> columns, String hostKeyFingerprint) {}

    /** How to authenticate: the scheme and every parameter required, never a value. */
    record Auth(String scheme, List<AuthParam> parameters, String docs) {}

    record AuthParam(String name, String in, boolean secret) {}
}
