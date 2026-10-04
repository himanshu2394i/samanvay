package in.samanvay.departments.kit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * This department's server calling Samanvay's department API with the credential Samanvay issued it (Keycloak client credentials).
 * The token is kept until shortly before it expires and never logged or passed to a browser. Samanvay decides the department from
 * the token, so nothing here can speak for another department.
 */
public class SamanvayClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SamanvayClient.class);
    private static final Duration EARLY = Duration.ofSeconds(30);
    /** The identity provider is a login step, not a batch job: if it does not answer in this time, the citizen is told it is unreachable. */
    static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(10);

    private record Token(String value, Instant expires) {}

    private final PortalProperties.Samanvay config;
    private final Clock clock;
    private final RestClient http;
    private final RestClient tokenHttp;
    private volatile Token token;

    public SamanvayClient(PortalProperties.Samanvay config, Clock clock) {
        this.config = config;
        this.clock = clock;
        // The JDK client (not HttpURLConnection): it hands back the body of a 401 instead of failing the call.
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(factory).build();
        java.net.http.HttpClient tokenClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory tokenFactory = new JdkClientHttpRequestFactory(tokenClient);
        tokenFactory.setReadTimeout(TOKEN_TIMEOUT);
        this.tokenHttp = RestClient.builder().requestFactory(tokenFactory).build();
    }

    // --- identity ----------------------------------------------------------------------------------------------

    /** The citizen who just signed in on this portal: {@code citizenId} and whether it was {@code created}. */
    public Map<String, Object> resolve(String assertion) {
        return post("/api/department/citizens/resolve", Map.of("assertion", assertion));
    }

    /** The other department's login address, set to bring the citizen back to {@code returnTo}. */
    public String startLink(UUID citizenId, String department, String returnTo) {
        return (String) post("/api/department/links/start", Map.of("citizenId", citizenId, "departmentCode", department, "returnTo", returnTo))
                .get("loginUrl");
    }

    /** Saves the link; returns the surviving citizen ID (it changes when two records of one person are merged). */
    public UUID completeLink(UUID citizenId, String department, String assertion) {
        return UUID.fromString((String) post("/api/department/links",
                Map.of("citizenId", citizenId, "departmentCode", department, "assertion", assertion)).get("citizenId"));
    }

    // --- journeys and consent --------------------------------------------------------------------------------

    public Map<String, Object> readiness(String journey, UUID citizenId) {
        return get("/api/department/journeys/" + journey(journey) + "/readiness?citizenId=" + citizenId, MAP);
    }

    public Map<String, Object> consentRequest(UUID citizenId, String journey) {
        return post("/api/department/consents/requests", Map.of("citizenId", citizenId, "journeyCode", journey(journey)));
    }

    public Map<String, Object> grantConsent(String statement) {
        return post("/api/department/consents", Map.of("statement", statement));
    }

    public Map<String, Object> start(String journey, UUID citizenId, Map<String, Object> submission) {
        return post("/api/journeys/" + journey(journey) + "/start", Map.of("citizenId", citizenId, "submission", submission));
    }

    // --- tracking ----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> applications(UUID citizenId) {
        return get("/api/applications?citizenId=" + citizenId, LIST);
    }

    public Map<String, Object> application(String reference) {
        return get("/api/applications/" + reference(reference), MAP);
    }

    public List<Map<String, Object>> steps(String reference) {
        return get("/api/applications/" + reference(reference) + "/steps", LIST);
    }

    public List<Map<String, Object>> issuedRecords(String reference) {
        return get("/api/applications/" + reference(reference) + "/issued-records", LIST);
    }

    // --- plumbing ----------------------------------------------------------------------------------------------

    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._@-]{1,100}");
    private static final Pattern JOURNEY = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    /** An application reference goes into a URL path: only the characters a reference has, and never "." or "..". */
    private static String reference(String ref) {
        if (ref == null || !REFERENCE.matcher(ref).matches() || ref.equals(".") || ref.equals("..")) {
            throw new SamanvayException(404, "No such application.");
        }
        return ref;
    }

    private static String journey(String code) {
        if (code == null || !JOURNEY.matcher(code).matches()) {
            throw new SamanvayException(404, "No such service.");
        }
        return code;
    }

    private Map<String, Object> post(String path, Object body) {
        return call(path, bearer -> http.post().uri(config.baseUrl() + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type) {
        return call(path, bearer -> http.get().uri(config.baseUrl() + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve().body(type));
    }

    /**
     * Runs a request with the cached token. A 401 that is only "your token is not good" (no reason, or UNAUTHENTICATED) means the
     * token was revoked or the identity provider was reset: the cached one is dropped and the call is tried ONCE more with a fresh
     * one. Any other 401 (for example LINK_PROOF_INVALID) is an answer, and is not repeated.
     */
    private <T> T call(String path, java.util.function.Function<String, T> request) {
        try {
            try {
                return request.apply(bearer());
            } catch (RestClientResponseException e) {
                String reason = field(e, "reason");
                if (e.getStatusCode().value() != 401 || (reason != null && !"UNAUTHENTICATED".equals(reason))) {
                    throw e;
                }
                token = null;
                return request.apply(bearer());
            }
        } catch (RestClientResponseException e) {
            throw new SamanvayException(e.getStatusCode().value(), detail(e), field(e, "reason"));
        } catch (SamanvayException e) {
            throw e;
        } catch (RuntimeException e) {
            // The exception text of a failed request holds its full URL, which carries a citizen ID: log the path (no query) and the kind only.
            log.warn("a call to Samanvay ({}) failed: {}", path.split("\\?", 2)[0], e.getClass().getSimpleName());
            throw new SamanvayException(503, "Samanvay could not be reached.");
        }
    }

    private static String detail(RestClientResponseException e) {
        String d = field(e, "detail");
        return d == null ? "Samanvay refused the request." : d;
    }

    private static String field(RestClientResponseException e, String name) {
        try {
            Object v = e.getResponseBodyAs(MAP).get(name);
            return v == null ? null : v.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The cached token, or a new one. The identity provider is called WITHOUT holding any lock, and with a short timeout: a slow or hung
     * identity provider delays the callers that need a token, but never blocks those that arrive meanwhile from trying themselves
     * (two callers may fetch a token at the same moment; the last one wins and both work).
     */
    private String bearer() {
        Token t = token;
        Instant now = clock.instant();
        if (t != null && now.isBefore(t.expires().minus(EARLY))) {
            return t.value();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        try {
            Map<String, Object> reply = tokenHttp.post().uri(config.tokenUrl()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(MAP);
            Object access = reply == null ? null : reply.get("access_token");
            if (!(access instanceof String value) || value.isBlank()) {
                log.warn("the identity provider's token reply has no access_token");
                throw new SamanvayException(503, "Samanvay could not be reached.");
            }
            long seconds = reply.get("expires_in") instanceof Number n ? Math.max(1, n.longValue()) : 60;
            token = new Token(value, now.plusSeconds(seconds));
            return value;
        } catch (SamanvayException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("the token request to Samanvay's identity provider failed: {}", e.getClass().getSimpleName());
            throw new SamanvayException(503, "Samanvay could not be reached.");
        }
    }
}
