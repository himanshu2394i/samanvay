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

    private final TokenService tokens;
    private final BankRecords records;

    DbtController(TokenService tokens, BankRecords records) {
        this.tokens = tokens;
        this.records = records;
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
        return records.find(dbtId.trim())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no bank account for this DBT ID")));
    }
}
