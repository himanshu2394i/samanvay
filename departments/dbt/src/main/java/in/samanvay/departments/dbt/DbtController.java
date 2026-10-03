package in.samanvay.departments.dbt;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** DBT's REST face: an OAuth2 token endpoint and the bearer-protected bank-account record. */
@RestController
class DbtController {

    /** The bank account DBT holds for a person (fake). One DBT ID unlocks it, so there is no resolve step. */
    record Bank(String accountRef, String ifscMasked, String holderName) {}

    private static final Map<String, Bank> BANK = Map.of(
            "DBT-1001", new Bank("XXXXXX1234", "SBIN0XXX300", "Asha Patil"),
            "DBT-1002", new Bank("XXXXXX5678", "HDFC0XXX210", "Ravi Deshmukh"));

    private final TokenService tokens;

    DbtController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret) {
        if (!"client_credentials".equals(grantType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        }
        return tokens.issue(clientId, clientSecret)
                .<ResponseEntity<Map<String, Object>>>map(t -> ResponseEntity.ok(Map.of(
                        "access_token", t.accessToken(), "token_type", "Bearer",
                        "expires_in", t.expiresInSeconds(), "scope", tokens.scope())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_client")));
    }

    /**
     * The bank record for a person, asked for with a POST and a JSON body {@code {"dbtId": "..."}}: a person ID does not
     * belong in a URL (access logs, proxies, browser history). The old GET-with-query form is gone.
     */
    @PostMapping(path = "/v1/bank", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> bank(@RequestBody Map<String, Object> body) {
        Object id = body.get("dbtId");
        if (!(id instanceof String dbtId) || dbtId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "dbtId (a string) is required in the JSON body"));
        }
        Bank bank = BANK.get(dbtId.trim());
        return bank == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no bank account for this DBT ID"))
                : ResponseEntity.ok(bank);
    }
}
