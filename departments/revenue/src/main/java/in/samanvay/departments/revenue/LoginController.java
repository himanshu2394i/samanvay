package in.samanvay.departments.revenue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Revenue's own citizen login (user ID + password) and the keys that let Samanvay verify what it issues.
 * Contract: docs/contracts/login-assertion.md. {@code return_to} must start with an allow-listed Samanvay address,
 * otherwise nothing is ever redirected (no open redirect).
 *
 * <p>ponytail: demo users from config, plaintext dev passwords, no lockout or captcha; a real user store and
 * throttling when this is more than a stand-in.
 */
@RestController
class LoginController {

    private record User(String username, byte[] password, String personId) {}

    private final String demoHint;
    private final AssertionSigner signer;
    private final List<String> allowedReturnUris;
    private final List<User> users;

    LoginController(AssertionSigner signer,
            @Value("${revenue.login.allowed-return-uris}") List<String> allowedReturnUris,
            @Value("${revenue.login.users}") List<String> users,
            @Value("${revenue.login.demo-hint:}") String demoHint) {
        this.demoHint = demoHint;
        this.signer = signer;
        this.allowedReturnUris = allowedReturnUris.stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
        this.users = users.stream().map(String::trim).filter(u -> !u.isEmpty()).map(u -> {
            String[] p = u.split("\\|");
            return new User(p[0], p[1].getBytes(StandardCharsets.UTF_8), p[2]);
        }).toList();
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    String jwks() {
        return signer.jwksJson();
    }

    @GetMapping(path = "/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        return invalidRequest(returnTo, state, nonce) ? badRequest() : ResponseEntity.ok(form(returnTo, state, nonce, null));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(@RequestParam String username, @RequestParam String password,
            @RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        if (invalidRequest(returnTo, state, nonce)) {
            return badRequest();
        }
        String personId = authenticate(username, password);
        if (personId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(form(returnTo, state, nonce, "Wrong user ID or password."));
        }
        String assertion = signer.sign(personId, state, nonce);
        String sep = returnTo.contains("?") ? "&" : "?";
        String location = returnTo + sep + "assertion=" + enc(assertion) + "&state=" + enc(state);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location).build();
    }

    /** The person ID for correct credentials, else null. Every user is compared so timing does not reveal who exists. */
    private String authenticate(String username, String password) {
        byte[] given = password.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (User u : users) {
            boolean passOk = MessageDigest.isEqual(u.password(), given);
            if (passOk & u.username().equals(username)) {
                found = u.personId();
            }
        }
        return found;
    }

    private boolean invalidRequest(String returnTo, String state, String nonce) {
        return returnTo == null || allowedReturnUris.stream().noneMatch(returnTo::startsWith)
                || state == null || state.isBlank() || nonce == null || nonce.isBlank();
    }

    private static ResponseEntity<String> badRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                .body("<p>Invalid login request (return address, state or nonce).</p>");
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private String form(String returnTo, String state, String nonce, String error) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Revenue Department - sign in</title><style>body{font:16px system-ui,sans-serif;max-width:26rem;margin:3rem auto;padding:0 1rem}label{display:block;margin:.8rem 0}input{display:block;width:100%;padding:.5rem;box-sizing:border-box}button{padding:.6rem 1.2rem}.hint{background:#fff7e0;padding:.5rem}[role=alert]{color:#b00020}</style></head><body>"
                + "<h1>Revenue Department</h1><p>Sign in with your Revenue user ID.</p>"
                + (error == null ? "" : "<p role=\"alert\">" + HtmlUtils.htmlEscape(error) + "</p>")
                + (demoHint == null || demoHint.isBlank() ? "" : "<p class=\"hint\">" + HtmlUtils.htmlEscape(demoHint) + "</p>")
                + "<form method=\"post\" action=\"/login\">"
                + "<label>User ID <input name=\"username\" autocomplete=\"username\" required></label>"
                + "<label>Password <input name=\"password\" type=\"password\" autocomplete=\"current-password\" required></label>"
                + hidden("return_to", returnTo) + hidden("state", state) + hidden("nonce", nonce)
                + "<button type=\"submit\">Sign in</button></form></body></html>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + HtmlUtils.htmlEscape(value) + "\">";
    }
}
