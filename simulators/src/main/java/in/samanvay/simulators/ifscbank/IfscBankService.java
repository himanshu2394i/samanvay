package in.samanvay.simulators.ifscbank;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic IFSC lookup and account validation over {@code fixtures/ifsc-bank.json}.
 * Field-by-field provenance: {@code fixtures/SPEC-NOTES.md}.
 */
@Service
class IfscBankService {

    /** Body marker on every JSON object this simulator returns (the header is added by {@link SimulatorHttpFilter}). */
    static final String MARKER_FIELD = "samanvay_simulator";

    /** RBI: 4-letter bank code, a reserved '0', then a 6-character alphanumeric branch code. */
    static final Pattern RBI_IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    enum Fault {
        TIMEOUT,
        SERVER_ERROR,
        MALFORMED;

        static Optional<Fault> fromHeader(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
    }

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final Map<String, JsonNode> branches = new LinkedHashMap<>();
    private final Map<String, JsonNode> accounts = new LinkedHashMap<>();
    private final Map<String, Fault> ifscFaults = new LinkedHashMap<>();
    private final Map<String, Fault> accountFaults = new LinkedHashMap<>();
    private final Clock clock;

    IfscBankService() throws IOException {
        this(Clock.systemUTC());
    }

    IfscBankService(Clock clock) throws IOException {
        this.clock = clock;
        JsonNode fixtures;
        try (InputStream in = new ClassPathResource("fixtures/ifsc-bank.json").getInputStream()) {
            fixtures = JsonMapper.builder().build().readTree(in);
        }
        fixtures.get("branches").forEach(b -> branches.put(b.get("IFSC").asString(), b));
        fixtures.get("accounts").forEach(a -> accounts.put(key(a.get("ifsc").asString(), a.get("account_number").asString()), a));
        fixtures.get("fault_triggers").get("ifsc").properties()
                .forEach(e -> ifscFaults.put(e.getKey(), Fault.fromHeader(e.getValue().asString()).orElseThrow()));
        fixtures.get("fault_triggers").get("account_number").properties()
                .forEach(e -> accountFaults.put(e.getKey(), Fault.fromHeader(e.getValue().asString()).orElseThrow()));
    }

    Optional<Fault> ifscFault(String ifsc) {
        return Optional.ofNullable(ifscFaults.get(ifsc.toUpperCase(Locale.ROOT)));
    }

    Optional<Fault> accountFault(JsonNode request) {
        return Optional.ofNullable(accountFaults.get(text(request.at("/fund_account/bank_account/account_number"))));
    }

    /** Razorpay IFSC API: 200 + branch object, else 404 with the JSON string "Not Found" (also for malformed codes). */
    ResponseEntity<?> lookupIfsc(String ifsc) {
        JsonNode branch = branches.get(ifsc.toUpperCase(Locale.ROOT));
        if (branch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("\"Not Found\"");
        }
        ObjectNode body = (ObjectNode) branch.deepCopy();
        body.put(MARKER_FIELD, true);
        return ResponseEntity.ok(body);
    }

    /** Razorpay X account validation (penny drop), synchronous "completed" outcome. */
    ResponseEntity<?> validateAccount(JsonNode request) {
        String name = text(request.at("/fund_account/bank_account/name"));
        String ifsc = text(request.at("/fund_account/bank_account/ifsc")).toUpperCase(Locale.ROOT);
        String accountNumber = text(request.at("/fund_account/bank_account/account_number"));
        if (name.isBlank()) {
            return badRequest("The name field is required.", "name");
        }
        if (accountNumber.isBlank()) {
            return badRequest("The account number field is required.", "account_number");
        }
        if (!RBI_IFSC.matcher(ifsc).matches() || !branches.containsKey(ifsc)) {
            return badRequest("Invalid IFSC Code in Bank Account", "ifsc");
        }

        JsonNode account = accounts.get(key(ifsc, accountNumber));
        String id = deterministicId(ifsc, accountNumber);
        ObjectNode results = nodes.objectNode();
        ObjectNode statusDetails = nodes.objectNode();
        String utr = null;
        if (account == null) {
            results.put("account_status", "invalid").putNull("registered_name");
            statusDetails.put("description", "Beneficiary account number is invalid.")
                    .put("source", "beneficiary_bank")
                    .put("reason", "invalid_account_number");
        } else if ("closed".equals(account.get("status").asString())) {
            results.put("account_status", "invalid").putNull("registered_name");
            statusDetails.put("description", "Beneficiary account is closed.")
                    .put("source", "beneficiary_bank")
                    .put("reason", "account_closed");
        } else {
            // Penny drop returns the bank's registered name; comparing it with the
            // supplied name (the "account mismatch" case) is the caller's job.
            results.put("account_status", "active").put("registered_name", account.get("holder_name").asString());
            statusDetails.put("description", "Account is valid.")
                    .put("source", "beneficiary_bank")
                    .put("reason", "success");
            utr = "SIMUTR" + id.substring(7).toUpperCase(Locale.ROOT);
        }
        statusDetails.put("reference_id", id);

        ObjectNode bankAccount = nodes.objectNode()
                .put("name", name)
                .put("bank_name", branches.get(ifsc).get("BANK").asString())
                .put("ifsc", ifsc)
                .put("account_number", accountNumber);
        ObjectNode fundAccount = nodes.objectNode()
                .put("id", "fa_" + id.substring(4))
                .put("entity", "fund_account")
                .put("account_type", "bank_account");
        fundAccount.set("bank_account", bankAccount);
        fundAccount.putNull("batch_id").put("active", true);

        ObjectNode body = nodes.objectNode().put("id", id).put("entity", "fund_account.validation");
        body.set("fund_account", fundAccount);
        body.put("status", "completed").put("amount", 100).put("currency", "INR");
        body.set("notes", request.has("notes") ? request.get("notes") : nodes.objectNode());
        body.set("results", results);
        body.set("status_details", statusDetails);
        body.put("created_at", clock.instant().getEpochSecond());
        if (utr == null) {
            body.putNull("utr");
        } else {
            body.put("utr", utr);
        }
        body.put(MARKER_FIELD, true);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> badRequest(String description, String field) {
        ObjectNode error = nodes.objectNode()
                .put("code", "BAD_REQUEST_ERROR")
                .put("description", description)
                .put("source", "business")
                .put("step", "NA")
                .put("reason", "input_validation_failed")
                .put("field", field);
        error.set("metadata", nodes.objectNode());
        ObjectNode body = nodes.objectNode();
        body.set("error", error);
        body.put(MARKER_FIELD, true);
        return ResponseEntity.badRequest().body(body);
    }

    private static String key(String ifsc, String accountNumber) {
        return ifsc.toUpperCase(Locale.ROOT) + "/" + accountNumber;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString().trim();
    }

    private static String deterministicId(String ifsc, String accountNumber) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((ifsc + "/" + accountNumber).getBytes(StandardCharsets.UTF_8));
            return "fav_SIM" + HexFormat.of().formatHex(digest, 0, 7);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
