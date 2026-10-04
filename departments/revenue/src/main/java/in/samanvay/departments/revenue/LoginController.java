package in.samanvay.departments.revenue;

import in.samanvay.departments.revenue.LoginPages.Brand;
import in.samanvay.departments.revenue.LoginPages.Request;
import in.samanvay.departments.kit.Person;
import in.samanvay.departments.kit.PortalProperties;
import in.samanvay.departments.kit.SignInThrottle;
import jakarta.servlet.http.HttpServletRequest;
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
 * <p>Both steps are throttled ({@link SignInThrottle}, shared with the portal): five wrong passwords lock a mobile, five wrong codes kill
 * a ticket, an address gets a larger allowance, and a ticket opens one login at most.
 *
 * <p>ponytail: the one-time code is one fixed value ({@code portal.otp-code}, never the default outside demo mode); a real department sends
 * a fresh code to the mobile and expires it. No captcha.
 */
@RestController
class LoginController {

    private static final Brand BRAND = new Brand("Revenue Department", "the Revenue Department", "R", "#0f5c6e", "#6cc0d4", "#06151b");
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);
    private static final String TOO_MANY = "Too many attempts. Please wait a few minutes and try again.";

    private final AssertionSigner signer;
    private final CitizenStore citizens;
    private final LoginPages pages;
    private final LoginTicket tickets = new LoginTicket(TICKET_TTL);
    private final byte[] code;
    private final SignInThrottle throttle;
    private final List<String> allowedReturnUris;

    LoginController(AssertionSigner signer, CitizenStore citizens,
            @Value("${revenue.login.allowed-return-uris}") List<String> allowedReturnUris,
            PortalProperties portal, SignInThrottle throttle,
            @Value("${revenue.login.demo-hint:}") String demoHint) {
        this.signer = signer;
        this.citizens = citizens;
        this.pages = new LoginPages(BRAND, demoHint);
        this.code = portal.otpCode().getBytes(StandardCharsets.UTF_8);
        this.throttle = throttle;
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
        if (returnTo == null && state == null && nonce == null) {
            // Opened directly (not from another department): this is the department's own portal sign in.
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/portal/#/sign-in").build();
        }
        return invalidRequest(returnTo, state, nonce) ? badRequest() : ResponseEntity.ok(pages.passwordPage(new Request(returnTo, state, nonce), null));
    }

    /** Step one: mobile and password. A correct pair earns a ticket and the code page, never the assertion itself. */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(HttpServletRequest http, @RequestParam String mobile, @RequestParam String password,
            @RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        if (invalidRequest(returnTo, state, nonce)) {
            return badRequest();
        }
        Request req = new Request(returnTo, state, nonce);
        String who = mobile.trim();
        String ip = http.getRemoteAddr();
        if (throttle.credentialsBlocked(who, ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(pages.passwordPage(req, TOO_MANY));
        }
        Optional<String> personId = who.length() > 40 || password.length() > 200 ? Optional.empty() : citizens.authenticate(who, password);
        if (personId.isEmpty()) {
            throttle.credentialsFailed(who, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pages.passwordPage(req, "The mobile number or password is not correct."));
        }
        throttle.credentialsOk(who);
        String ticket = tickets.issue(personId.get(), state, nonce, Instant.now());
        return ResponseEntity.ok(pages.codePage(req, ticket, "ending " + lastFour(mobile), null));
    }

    /** Step two: the one-time code. Only a genuine ticket for THIS login plus the right code earns the signed assertion. */
    @PostMapping(path = "/login/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> verify(HttpServletRequest http, @RequestParam String ticket, @RequestParam String code,
            @RequestParam(name = "masked", required = false) String masked,
            @RequestParam(name = "return_to", required = false) String returnTo,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce) {
        if (invalidRequest(returnTo, state, nonce)) {
            return badRequest();
        }
        Request req = new Request(returnTo, state, nonce);
        String ip = http.getRemoteAddr();
        if (throttle.codeBlocked(ticket, ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(pages.passwordPage(req, TOO_MANY));
        }
        Optional<String> personId = tickets.verify(ticket, state, nonce, Instant.now());
        if (personId.isEmpty()) {
            throttle.codeFailed(ticket, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(pages.passwordPage(req, "Your sign in took too long or could not be checked. Please start again."));
        }
        if (!MessageDigest.isEqual(this.code, code.trim().getBytes(StandardCharsets.UTF_8))) {
            throttle.codeFailed(ticket, ip);
            String shownMask = masked != null && masked.matches("ending [0-9]{4}") ? masked : "registered with " + BRAND.shortName();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(pages.codePage(req, ticket, shownMask, "That code is not correct. Check it and try again."));
        }
        if (!throttle.useTicket(ticket)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pages.passwordPage(req, "This sign in was already used. Please start again."));
        }
        String assertion = signer.sign(citizens.person(personId.get()).orElse(new Person(personId.get(), null, null)), state, nonce);
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
