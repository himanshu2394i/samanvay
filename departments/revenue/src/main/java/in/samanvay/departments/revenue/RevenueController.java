package in.samanvay.departments.revenue;

import in.samanvay.departments.revenue.RevenueRecords.Doc;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Revenue's REST face: resolve a person's certificates, then fetch one by its key. */
@RestController
class RevenueController {

    private static final Map<String, String> TYPE_BY_PATH = Map.of(
            "income", "INCOME_CERTIFICATE", "caste", "CASTE_CERTIFICATE", "domicile", "DOMICILE_CERTIFICATE");

    private final RevenueRecords records;

    RevenueController(RevenueRecords records) {
        this.records = records;
    }

    /** Resolve: person ID (+ API key) -> the keys of the certificates of one type that person holds. */
    @GetMapping(path = "/v1/persons/{personId}/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> resolve(@PathVariable String personId, @RequestParam String type) {
        if (!records.personExists(personId)) {
            return error(HttpStatus.NOT_FOUND, "unknown person");
        }
        String latestKey = records.latest(personId, type).map(Doc::key).orElse(null);
        List<Map<String, Object>> docs = records.forPerson(personId, type).stream()
                .map(d -> Map.<String, Object>of("key", d.key(), "issuedOn", d.issuedOn().toString(), "latest", d.key().equals(latestKey)))
                .toList();
        return ResponseEntity.ok(Map.of("personId", personId, "type", type, "documents", docs));
    }

    /** Fetch one certificate by its key; the path says which kind (a caste key is not an income document). */
    @GetMapping(path = "/v1/{kind}/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> fetch(@PathVariable String kind, @PathVariable String key) {
        String type = TYPE_BY_PATH.get(kind);
        return type == null ? error(HttpStatus.NOT_FOUND, "unknown document kind")
                : records.byKey(type, key)
                        .map(d -> ResponseEntity.ok(withKey(d)))
                        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "no such document"));
    }

    private static Map<String, Object> withKey(Doc d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", d.key());
        body.put("issuedOn", d.issuedOn().toString());
        // Only what the manifest declares for this kind of certificate, not every key the stored row happens to hold.
        for (RevenueManifestController.Field f : RevenueManifestController.DECLARED.getOrDefault(d.type(), List.of())) {
            if (d.fields().containsKey(f.name())) {
                body.put(f.name(), d.fields().get(f.name()));
            }
        }
        return body;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
