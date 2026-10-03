package in.samanvay.departments.revenue;

import in.samanvay.departments.revenue.LoginPages.Brand;
import in.samanvay.departments.revenue.LoginPages.Request;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Revenue's own citizen login: mobile number and password, then a one-time code, and the keys that let Samanvay verify what it
 * issues. Contract: docs/contracts/login-assertion.md. {@code return_to} must start with an allow-listed Samanvay address,
 * otherwise nothing is ever redirected (no open redirect).
 *
 * <p>ponytail: the one-time code is one fixed demo value ({@code revenue.login.code}); a real department sends a fresh code to
 * the mobile and expires it. No lockout or captcha either.
 */
@RestController
class LoginController {

    private static final Brand BRAND = new Brand("Revenue Department", "the Revenue Department", "R", "#0f5c6e", "#6cc0d4", "#06151b");
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private final AssertionSigner signer;
    private final CitizenStore citizens;
    private final LoginPages pages;
    private final LoginTicket tickets = new LoginTicket(TICKET_TTL);
    private final byte[] code;
    private final List<String> allowedReturnUris;

    LoginController(AssertionSigner signer, CitizenStore citizens,
            @Value("${revenue.login.allowed-return-uris}") List<String> allowedReturnUris,
            @Value("${revenue.login.code}") String code,
            @Value("${revenue.login.demo-hint:}") String demoHint) {
        this.signer = signer;
        this.citizens = citizens;
        this.pages = new LoginPages(BRAND, demoHint);
        this.code = code.getBytes(StandardCharsets.UTF_8);
        this.allowedReturnUris = allowedReturnUris.stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    String jwks() {
        return signer.jwksJson();
    }

    @GetMapping(path = "/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        return invalidRequest(returnTo, state, nonce) ? badRequest() : ResponseEntity.ok(pages.passwordPage(new Request(returnTo, state, nonce), null));
    }

    /** Step one: mobile and password. A correct pair earns a ticket and the code page, never the assertion itself. */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(@RequestParam String mobile, @RequestParam String password,
            @RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        if (invalidRequest(returnTo, state, nonce)) {
            return badRequest();
        }
        Request req = new Request(returnTo, state, nonce);
        Optional<String> personId = citizens.authenticate(mobile.trim(), password);
        if (personId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pages.passwordPage(req, "The mobile number or password is not correct."));
        }
        String ticket = tickets.issue(personId.get(), state, nonce, Instant.now());
        return ResponseEntity.ok(pages.codePage(req, ticket, "ending " + lastFour(mobile), null));
    }

    /** Step two: the one-time code. Only a genuine ticket for THIS login plus the right code earns the signed assertion. */
    @PostMapping(path = "/login/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> verify(@RequestParam String ticket, @RequestParam String code,
            @RequestParam(name = "masked", required = false) String masked,
            @RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        if (invalidRequest(returnTo, state, nonce)) {
            return badRequest();
        }
        Request req = new Request(returnTo, state, nonce);
        Optional<String> personId = tickets.verify(ticket, state, nonce, Instant.now());
        if (personId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(pages.passwordPage(req, "Your sign in took too long or could not be checked. Please start again."));
        }
        if (!MessageDigest.isEqual(this.code, code.trim().getBytes(StandardCharsets.UTF_8))) {
            String shownMask = masked != null && masked.matches("ending [0-9]{4}") ? masked : "registered with " + BRAND.shortName();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(pages.codePage(req, ticket, shownMask, "That code is not correct. Check it and try again."));
        }
        String assertion = signer.sign(personId.get(), state, nonce);
        String sep = returnTo.contains("?") ? "&" : "?";
        String location = returnTo + sep + "assertion=" + enc(assertion) + "&state=" + enc(state);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location).build();
    }

    private static String lastFour(String mobile) {
        String digits = mobile.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : "0000";
    }

    private boolean invalidRequest(String returnTo, String state, String nonce) {
        return returnTo == null || allowedReturnUris.stream().noneMatch(returnTo::startsWith)
                || state == null || state.isBlank() || nonce == null || nonce.isBlank();
    }

    private static ResponseEntity<String> badRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body(LoginPages.invalidRequest());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
