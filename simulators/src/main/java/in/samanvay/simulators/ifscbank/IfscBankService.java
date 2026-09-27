package in.samanvay.simulators.ifscbank;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic IFSC lookup and bank check over {@code fixtures/ifsc-bank.json}.
 * Contract: {@code docs/contracts/bank-check-v1.yaml}. Field provenance: {@code fixtures/SPEC-NOTES.md}.
 *
 * <p>No matching logic: each fixture account carries a FIXED {@code account_status}
 * and {@code name_match}; {@code applicantName} is only checked for presence. The
 * fixture {@code holder_name} (a canary string) is never read into any response.
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

    /** Only the outcome is kept in memory: the holder name is deliberately not loaded. */
    private record Outcome(String accountStatus, String nameMatch) {}

    private final Map<String, JsonNode> branches = new LinkedHashMap<>();
    private final Map<String, Outcome> accounts = new LinkedHashMap<>();
    private final Map<String, Fault> ifscFaults = new LinkedHashMap<>();
    private final Map<String, Fault> accountFaults = new LinkedHashMap<>();

    IfscBankService() throws IOException {
        JsonNode fixtures;
        try (InputStream in = new ClassPathResource("fixtures/ifsc-bank.json").getInputStream()) {
            fixtures = JsonMapper.builder().build().readTree(in);
        }
        fixtures.get("branches").forEach(b -> branches.put(b.get("IFSC").asString(), b));
        fixtures.get("accounts").forEach(a -> accounts.put(
                key(a.get("ifsc").asString(), a.get("account_number").asString()),
                new Outcome(a.get("account_status").asString(), a.get("name_match").asString())));
        fixtures.get("fault_triggers").get("ifsc").properties()
                .forEach(e -> ifscFaults.put(e.getKey(), Fault.fromHeader(e.getValue().asString()).orElseThrow()));
        fixtures.get("fault_triggers").get("account_number").properties()
                .forEach(e -> accountFaults.put(e.getKey(), Fault.fromHeader(e.getValue().asString()).orElseThrow()));
    }

    Optional<Fault> ifscFault(String ifsc) {
        return Optional.ofNullable(ifscFaults.get(ifsc.toUpperCase(Locale.ROOT)));
    }

    /** A bank check faults on a trigger account number OR a trigger IFSC (so any account number can ride a fault). */
    Optional<Fault> accountFault(JsonNode request) {
        return Optional.ofNullable(accountFaults.get(text(request, "accountNumber")))
                .or(() -> ifscFault(text(request, "ifsc")));
    }

    /** 200 + branch object (open RBI data), else 404 with the JSON string "Not Found" (also for malformed codes). */
    ResponseEntity<?> lookupIfsc(String ifsc) {
        JsonNode branch = branches.get(ifsc.toUpperCase(Locale.ROOT));
        if (branch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("\"Not Found\"");
        }
        ObjectNode body = (ObjectNode) branch.deepCopy();
        body.put(MARKER_FIELD, true);
        return ResponseEntity.ok(body);
    }

    /** POST /v1/bank-checks: {@code {accountStatus, nameMatch}} and nothing else (never the holder's name). */
    ResponseEntity<?> bankCheck(JsonNode request) {
        String ifsc = text(request, "ifsc").toUpperCase(Locale.ROOT);
        String accountNumber = text(request, "accountNumber");
        String applicantName = text(request, "applicantName");
        List<String> invalid = new ArrayList<>();
        if (!RBI_IFSC.matcher(ifsc).matches()) {
            invalid.add("ifsc");
        }
        if (!accountNumber.matches("^[0-9]{6,18}$")) {
            invalid.add("accountNumber");
        }
        if (applicantName.isBlank()) {
            invalid.add("applicantName");
        }
        if (!invalid.isEmpty()) {
            ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more fields are invalid.");
            problem.setProperty("invalidParams", invalid.stream().map(n -> Map.of("name", n)).toList());
            return problemResponse(problem);
        }

        Outcome outcome = branches.containsKey(ifsc) ? accounts.get(key(ifsc, accountNumber)) : null;
        if (outcome == null) {
            outcome = new Outcome("INVALID", "NOT_CHECKED");
        }
        return ResponseEntity.ok(Map.of(
                "accountStatus", outcome.accountStatus(),
                "nameMatch", outcome.nameMatch(),
                MARKER_FIELD, true));
    }

    static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://samanvay.dev/contracts/bank-check/v1/problems/"
                + status.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty(MARKER_FIELD, true);
        return problem;
    }

    static ResponseEntity<?> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String key(String ifsc, String accountNumber) {
        return ifsc.toUpperCase(Locale.ROOT) + "/" + accountNumber;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? "" : node.asString().trim();
    }
}
