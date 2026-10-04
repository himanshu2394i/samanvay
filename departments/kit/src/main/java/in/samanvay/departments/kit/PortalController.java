package in.samanvay.departments.kit;

import in.samanvay.departments.kit.JourneyCatalog.Journey;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The department portal's backend: everything the portal pages (served at /portal/) ask of this department's server. The browser
 * only ever talks to the department; this class talks to Samanvay for it, with the department's own credential. The session is a
 * signed HttpOnly cookie; all writes are JSON only, so a form on another site cannot make a request that carries it.
 *
 * <p>Sign in is throttled ({@link SignInThrottle}): wrong passwords and wrong codes are counted per mobile, per address and per
 * ticket, and a ticket opens one session at most.
 *
 * <p>ponytail: pending consent wordings are kept in memory, so a restart makes the citizen ask for the wording again; and one
 * server only (not shared between instances).
 */
@RestController
public class PortalController {

    static final String COOKIE = "dept_session";
    static final Duration PENDING_TTL = Duration.ofMinutes(10);
    /** A citizen can have this many consent wordings waiting; asking for another drops the oldest. */
    static final int PENDING_PER_CITIZEN = 5;
    /** All citizens together; beyond this a new request is refused until old ones expire. */
    static final int PENDING_MAX = 10_000;
    private static final Logger log = LoggerFactory.getLogger(PortalController.class);
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,80}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._@-]{1,100}");

    private record Pending(UUID citizenId, String journey, Map<String, Object> wording, Instant expires, long seq) {}

    private final PortalProperties props;
    private final PortalSession sessions;
    private final SamanvayClient samanvay;
    private final JourneyCatalog catalog;
    private final CitizenDirectory directory;
    private final HomeAssertions home;
    private final ConsentSigner signer;
    private final SignInThrottle throttle;
    private final Clock clock;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong pendingSeq = new java.util.concurrent.atomic.AtomicLong();

    PortalController(PortalProperties props, PortalSession sessions, SamanvayClient samanvay, JourneyCatalog catalog, CitizenDirectory directory,
            HomeAssertions home, ConsentSigner signer, SignInThrottle throttle, Clock clock) {
        this.props = props;
        this.sessions = sessions;
        this.samanvay = samanvay;
        this.catalog = catalog;
        this.directory = directory;
        this.home = home;
        this.signer = signer;
        this.throttle = throttle;
        this.clock = clock;
    }

    // --- sign in -----------------------------------------------------------------------------------------------

    @GetMapping("/portal-api/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", props.deptCode());
        m.put("name", props.name());
        m.put("initial", props.initial());
        m.put("accent", props.accent());
        m.put("accentDark", props.accentDark());
        m.put("onAccentDark", props.onAccentDark());
        return m;
    }

    record SignIn(String mobile, String password) {}

    /** Step one: mobile and password. A correct pair earns a short-lived ticket for the code step, never a session. */
    @PostMapping("/portal-api/sign-in")
    Map<String, Object> signIn(HttpServletRequest req, @RequestBody SignIn body) {
        if (body == null || body.mobile() == null || body.password() == null || body.mobile().length() > 40 || body.password().length() > 200) {
            throw new Refusal(HttpStatus.BAD_REQUEST, "Enter your registered mobile number and password.");
        }
        String mobile = body.mobile().trim();
        String ip = req.getRemoteAddr();
        if (throttle.credentialsBlocked(mobile, ip)) {
            throw tooMany();
        }
        Optional<String> found = directory.authenticate(mobile, body.password());
        if (found.isEmpty()) {
            throttle.credentialsFailed(mobile, ip);
            throw new Refusal(HttpStatus.UNAUTHORIZED, "The mobile number or password is not correct.");
        }
        throttle.credentialsOk(mobile);
        String digits = mobile.replaceAll("[^0-9]", "");
        String last = digits.length() >= 4 ? digits.substring(digits.length() - 4) : "0000";
        return Map.of("ticket", sessions.issueTicket(found.get(), clock.instant()), "masked", "ending " + last);
    }

    record Verify(String ticket, String code) {}

    /** Step two: the one-time code. Only a genuine, unused ticket plus the right code gives a session. */
    @PostMapping("/portal-api/verify")
    ResponseEntity<Map<String, Object>> verify(HttpServletRequest req, @RequestBody Verify body) {
        String ticket = body == null ? null : body.ticket();
        String ip = req.getRemoteAddr();
        if (throttle.codeBlocked(ticket, ip)) {
            throw tooMany();
        }
        String personId = sessions.readTicket(ticket, clock.instant()).orElseThrow(() -> {
            throttle.codeFailed(ticket, ip);
            return new Refusal(HttpStatus.UNAUTHORIZED, "Your sign in took too long. Please start again.");
        });
        if (!codeIsRight(body.code())) {
            throttle.codeFailed(ticket, ip);
            throw wrongCode();
        }
        Person person = directory.person(personId).orElseThrow(() -> new Refusal(HttpStatus.UNAUTHORIZED, "Your sign in could not be completed."));
        UUID citizenId = UUID.fromString((String) samanvay.resolve(home.issue(person)).get("citizenId"));
        if (!throttle.useTicket(ticket)) {
            throw new Refusal(HttpStatus.UNAUTHORIZED, "This sign in was already used. Please start again.");
        }
        PortalSession.Session session = new PortalSession.Session(person.personId(), citizenId, person.name());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(sessions.issue(session, clock.instant()), PortalSession.SESSION_TTL).toString())
                .body(me(session));
    }

    @PostMapping("/portal-api/sign-out")
    ResponseEntity<Void> signOut() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/portal-api/me")
    Map<String, Object> me(HttpServletRequest req) {
        return me(session(req));
    }

    private Map<String, Object> me(PortalSession.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("personId", s.personId());
        m.put("name", s.name());
        m.put("department", props.deptCode());
        return m;
    }

    // --- journeys ----------------------------------------------------------------------------------------------

    @GetMapping("/portal-api/journeys")
    List<Journey> journeys(HttpServletRequest req) {
        session(req);
        return catalog.all();
    }

    @GetMapping("/portal-api/journeys/{code}")
    Journey journey(HttpServletRequest req, @PathVariable String code) {
        session(req);
        return journeyOf(code);
    }

    @GetMapping("/portal-api/journeys/{code}/readiness")
    Map<String, Object> readiness(HttpServletRequest req, @PathVariable String code) {
        PortalSession.Session s = session(req);
        journeyOf(code);
        return samanvay.readiness(code, s.citizenId());
    }

    // --- connecting the other departments --------------------------------------------------------------------

    /** Where to send the citizen's browser to log in at {@code dept}; it comes back to {@link #callback}. */
    @PostMapping("/portal-api/journeys/{code}/links/{dept}")
    Map<String, Object> startLink(HttpServletRequest req, @PathVariable String code, @PathVariable String dept) {
        PortalSession.Session s = session(req);
        journeyOf(code);
        requireCode(dept, "department");
        String returnTo = props.publicBaseUrl() + "/portal/callback?dept=" + dept + "&journey=" + code;
        return Map.of("loginUrl", samanvay.startLink(s.citizenId(), dept, returnTo));
    }

    /** The other department sent the citizen back with its signed assertion. Samanvay checks it; the browser only gets a redirect. */
    @GetMapping("/portal/callback")
    void callback(HttpServletRequest req, HttpServletResponse res, @RequestParam String dept, @RequestParam(required = false) String journey,
            @RequestParam(required = false) String assertion) throws java.io.IOException {
        if (!CODE.matcher(dept).matches() || (journey != null && !CODE.matcher(journey).matches())) {
            res.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String back = "/portal/#/" + (journey == null ? "" : "journeys/" + journey);
        Optional<PortalSession.Session> s = sessions.read(cookieValue(req), clock.instant());
        if (s.isEmpty()) {
            res.sendRedirect("/portal/#/sign-in");
            return;
        }
        String outcome = "linkError";
        if (assertion != null && !assertion.isBlank()) {
            try {
                UUID survivor = samanvay.completeLink(s.get().citizenId(), dept, assertion);
                if (!survivor.equals(s.get().citizenId())) {
                    PortalSession.Session merged = new PortalSession.Session(s.get().personId(), survivor, s.get().name());
                    res.addHeader(HttpHeaders.SET_COOKIE, cookie(sessions.issue(merged, clock.instant()), PortalSession.SESSION_TTL).toString());
                }
                outcome = "linked";
            } catch (SamanvayException e) {
                log.info("linking {} was refused: {}", dept, e.status());
            }
        }
        res.sendRedirect(back + "?" + outcome + "=" + dept);
    }

    // --- consent -----------------------------------------------------------------------------------------------

    /** The exact wording to show. The signing nonce stays on the server. */
    @GetMapping("/portal-api/journeys/{code}/consent")
    Map<String, Object> consentWording(HttpServletRequest req, @PathVariable String code) {
        PortalSession.Session s = session(req);
        journeyOf(code);
        Map<String, Object> wording = samanvay.consentRequest(s.citizenId(), code);
        // Samanvay must have sent what a statement repeats; never keep (or later sign) a wording with a hole in it.
        if (!(wording.get("requestId") instanceof String id) || id.isBlank() || !(wording.get("nonce") instanceof String n) || n.isBlank()) {
            log.warn("Samanvay's consent request for {} has no requestId or nonce", code);
            throw new Refusal(HttpStatus.BAD_GATEWAY, "Samanvay sent an incomplete consent request. Please try again later.");
        }
        remember(id, new Pending(s.citizenId(), code, wording, clock.instant().plus(PENDING_TTL), pendingSeq.incrementAndGet()));
        Map<String, Object> shown = new LinkedHashMap<>(wording);
        shown.remove("nonce");
        shown.remove("expiresAt");
        return shown;
    }

    /** Keeps the wording until it is confirmed or expires; a citizen's oldest ones are dropped beyond {@link #PENDING_PER_CITIZEN}. */
    private synchronized void remember(String requestId, Pending p) {
        Instant now = clock.instant();
        pending.values().removeIf(x -> x.expires().isBefore(now));
        List<Map.Entry<String, Pending>> mine = new java.util.ArrayList<>(pending.entrySet().stream()
                .filter(e -> e.getValue().citizenId().equals(p.citizenId())).sorted(Comparator.comparingLong(e -> e.getValue().seq())).toList());
        while (mine.size() >= PENDING_PER_CITIZEN) {
            pending.remove(mine.remove(0).getKey());
        }
        if (pending.size() >= PENDING_MAX) {
            throw new Refusal(HttpStatus.SERVICE_UNAVAILABLE, "Too many requests are waiting. Please try again in a few minutes.");
        }
        pending.put(requestId, p);
    }

    record Confirm(String requestId, String code) {}

    /** The citizen confirms with the one-time code; the department signs the statement and Samanvay records the consent. */
    @PostMapping("/portal-api/journeys/{code}/consent")
    Map<String, Object> confirmConsent(HttpServletRequest req, @PathVariable String code, @RequestBody Confirm body) {
        PortalSession.Session s = session(req);
        journeyOf(code);
        Pending p = body == null || body.requestId() == null ? null : pending.get(body.requestId());
        if (p == null || !p.citizenId().equals(s.citizenId()) || !p.journey().equals(code) || p.expires().isBefore(clock.instant())) {
            throw new Refusal(HttpStatus.GONE, "This consent request has expired. Go back and review it again.");
        }
        if (!codeIsRight(body.code())) {
            throw wrongCode();
        }
        String statement = signer.sign(p.wording(), s.citizenId(), clock.instant());
        samanvay.grantConsent(statement);
        pending.remove(body.requestId());
        return Map.of("granted", true);
    }

    // --- submitting and tracking --------------------------------------------------------------------------------

    record Submit(Map<String, Object> submission) {}

    @PostMapping("/portal-api/journeys/{code}/submit")
    Map<String, Object> submit(HttpServletRequest req, @PathVariable String code, @RequestBody Submit body) {
        PortalSession.Session s = session(req);
        Journey j = journeyOf(code);
        Map<String, Object> clean = new LinkedHashMap<>();
        Map<String, Object> given = body == null || body.submission() == null ? Map.of() : body.submission();
        for (JourneyCatalog.Field f : j.form()) {
            Object v = given.get(f.name());
            String text = v == null ? "" : v.toString().trim();
            if (text.isEmpty()) {
                if (f.required()) {
                    throw new Refusal(HttpStatus.BAD_REQUEST, f.label() + " is required.");
                }
                continue;
            }
            if (text.length() > 500 || (f.options() != null && !f.options().isEmpty() && !f.options().contains(text))) {
                throw new Refusal(HttpStatus.BAD_REQUEST, f.label() + " is not valid.");
            }
            clean.put(f.name(), text);
        }
        return samanvay.start(code, s.citizenId(), clean);
    }

    @GetMapping("/portal-api/applications")
    List<Map<String, Object>> applications(HttpServletRequest req) {
        return samanvay.applications(session(req).citizenId());
    }

    @GetMapping("/portal-api/applications/{ref}")
    Map<String, Object> application(HttpServletRequest req, @PathVariable String ref) {
        return own(req, ref);
    }

    @GetMapping("/portal-api/applications/{ref}/steps")
    List<Map<String, Object>> steps(HttpServletRequest req, @PathVariable String ref) {
        own(req, ref);
        return samanvay.steps(ref);
    }

    @GetMapping("/portal-api/applications/{ref}/records")
    List<Map<String, Object>> records(HttpServletRequest req, @PathVariable String ref) {
        own(req, ref);
        return samanvay.issuedRecords(ref);
    }

    /** The application, if it belongs to the signed-in citizen; anyone else's (or a reference that cannot exist) is simply not found. */
    private Map<String, Object> own(HttpServletRequest req, String ref) {
        PortalSession.Session s = session(req);
        if (ref == null || !REFERENCE.matcher(ref).matches() || ref.equals(".") || ref.equals("..")) {
            throw new Refusal(HttpStatus.NOT_FOUND, "No such application.");
        }
        Map<String, Object> app = samanvay.application(ref);
        if (!s.citizenId().toString().equals(String.valueOf(app.get("citizenId")))) {
            throw new Refusal(HttpStatus.NOT_FOUND, "No such application.");
        }
        return app;
    }

    // --- plumbing ----------------------------------------------------------------------------------------------

    private PortalSession.Session session(HttpServletRequest req) {
        return sessions.read(cookieValue(req), clock.instant()).orElseThrow(() -> new Refusal(HttpStatus.UNAUTHORIZED, "Sign in to continue."));
    }

    private static String cookieValue(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(props.secureCookies()).sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    private Journey journeyOf(String code) {
        return catalog.byCode(code).orElseThrow(() -> new Refusal(HttpStatus.NOT_FOUND, "No such service."));
    }

    private boolean codeIsRight(String code) {
        return code != null && MessageDigest.isEqual(props.otpCode().getBytes(StandardCharsets.UTF_8), code.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static Refusal wrongCode() {
        return new Refusal(HttpStatus.UNAUTHORIZED, "That code is not correct. Check it and try again.");
    }

    private static Refusal tooMany() {
        return new Refusal(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes and try again.");
    }

    private static void requireCode(String value, String what) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new Refusal(HttpStatus.BAD_REQUEST, "Not a valid " + what + ".");
        }
    }

    /** A refusal the portal shows to the citizen as is. */
    static final class Refusal extends RuntimeException {
        final HttpStatus status;

        Refusal(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    @ExceptionHandler(Refusal.class)
    ResponseEntity<Map<String, Object>> refused(Refusal e) {
        return ResponseEntity.status(e.status).body(Map.of("detail", e.getMessage()));
    }

    /**
     * Samanvay's own refusals pass through with their status; our credential problems and outages become 502 and 503. Samanvay's
     * wording reaches the browser only if it reads like a sentence for a citizen ({@link UpstreamText}); anything else becomes a
     * generic line for that status, and the original goes to the log.
     */
    @ExceptionHandler(SamanvayException.class)
    ResponseEntity<Map<String, Object>> samanvayRefused(SamanvayException e) {
        int s = e.status();
        if ("LINK_PROOF_INVALID".equals(e.reason())) {
            // Samanvay reached and understood us, but could not verify the department's sign in (for example the department is not connected yet).
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Samanvay could not verify this sign in. The department may not be connected to Samanvay yet."));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (s == 401 || s == 403) {
            body.put("detail", "Samanvay did not accept this department's request.");
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
        if (s >= 500 || s < 400) {
            log.info("Samanvay answered {}: {}", s, e.getMessage());
            body.put("detail", "Samanvay could not be reached right now. Please try again later.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        HttpStatus status = HttpStatus.resolve(s) == null ? HttpStatus.BAD_REQUEST : HttpStatus.resolve(s);
        body.put("detail", UpstreamText.forCitizen(status.value(), e.getMessage()));
        if (e.reason() != null && e.reason().matches("[A-Z0-9_]{1,60}")) {
            body.put("reason", e.reason());
        }
        return ResponseEntity.status(status).body(body);
    }
}
