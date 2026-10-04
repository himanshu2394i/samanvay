package in.samanvay.departments.kit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private static final Duration EARLY = Duration.ofSeconds(30);

    private final PortalProperties.Samanvay config;
    private final Clock clock;
    private final RestClient http;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public SamanvayClient(PortalProperties.Samanvay config, Clock clock) {
        this.config = config;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(factory).build();
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
        return get("/api/department/journeys/" + journey + "/readiness?citizenId=" + citizenId, MAP);
    }

    public Map<String, Object> consentRequest(UUID citizenId, String journey) {
        return post("/api/department/consents/requests", Map.of("citizenId", citizenId, "journeyCode", journey));
    }

    public Map<String, Object> grantConsent(String statement) {
        return post("/api/department/consents", Map.of("statement", statement));
    }

    public Map<String, Object> start(String journey, UUID citizenId, Map<String, Object> submission) {
        return post("/api/journeys/" + journey + "/start", Map.of("citizenId", citizenId, "submission", submission));
    }

    // --- tracking ----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> applications(UUID citizenId) {
        return get("/api/applications?citizenId=" + citizenId, LIST);
    }

    public Map<String, Object> application(String reference) {
        return get("/api/applications/" + reference, MAP);
    }

    public List<Map<String, Object>> steps(String reference) {
        return get("/api/applications/" + reference + "/steps", LIST);
    }

    public List<Map<String, Object>> issuedRecords(String reference) {
        return get("/api/applications/" + reference + "/issued-records", LIST);
    }

    // --- plumbing ----------------------------------------------------------------------------------------------

    private Map<String, Object> post(String path, Object body) {
        return call(() -> http.post().uri(config.baseUrl() + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type) {
        return call(() -> http.get().uri(config.baseUrl() + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer()).retrieve().body(type));
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new SamanvayException(e.getStatusCode().value(), detail(e));
        } catch (SamanvayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SamanvayException(503, "Samanvay could not be reached.");
        }
    }

    private static String detail(RestClientResponseException e) {
        try {
            Object d = e.getResponseBodyAs(MAP).get("detail");
            return d == null ? "Samanvay refused the request." : d.toString();
        } catch (RuntimeException ignored) {
            return "Samanvay refused the request.";
        }
    }

    private synchronized String bearer() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpires.minus(EARLY))) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        try {
            Map<String, Object> reply = http.post().uri(config.tokenUrl()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(MAP);
            token = (String) reply.get("access_token");
            tokenExpires = now.plusSeconds(((Number) reply.getOrDefault("expires_in", 60)).longValue());
            return token;
        } catch (RuntimeException e) {
            throw new SamanvayException(503, "Samanvay could not be reached.");
        }
    }
}
