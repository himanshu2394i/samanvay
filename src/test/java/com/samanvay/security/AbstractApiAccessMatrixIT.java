package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.security.ApiAccessMatrix.Who;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestTokens;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every live {@code /api/**} route x {anonymous, citizen, officer, reviewer,
 * admin, department client}: 401 for anonymous, 403 for a role the matrix does
 * not allow, and for an allowed role neither 401/403 nor a 5xx (the actual
 * 2xx/4xx then depends on the dummy input; a server error on dummy input is a
 * bug, not a pass). Routes come from
 * {@link RequestMappingHandlerMapping}, so a new endpoint that is not in
 * {@link ApiAccessMatrix} fails this test.
 */
abstract class AbstractApiAccessMatrixIT extends PostgresIntegrationTest {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}/]+)}");

    @LocalServerPort
    int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    CitizenProfiles profiles;

    abstract boolean demoProfile();

    @Test
    void everyLiveRouteIsInTheMatrix() {
        Set<String> live = liveRoutes();
        assertThat(live).as("live /api routes").isNotEmpty();
        Set<String> unlisted = new TreeSet<>(live);
        unlisted.removeAll(ApiAccessMatrix.ALLOWED.keySet());
        assertThat(unlisted)
                .as("routes missing from ApiAccessMatrix (add them with their allowed roles)")
                .isEmpty();
        Set<String> stale = new TreeSet<>(ApiAccessMatrix.ALLOWED.keySet());
        stale.removeAll(live);
        if (!demoProfile()) {
            stale.removeAll(ApiAccessMatrix.DEMO_ONLY);
        }
        assertThat(stale).as("matrix entries with no live route").isEmpty();
        if (!demoProfile()) {
            assertThat(live).doesNotContainAnyElementsOf(ApiAccessMatrix.DEMO_ONLY);
        }
    }

    @Test
    void everyRouteAnswersEveryCallerAsTheMatrixSays() {
        String citizenSubject = "matrix-citizen-" + UUID.randomUUID();
        UUID ownCitizen = profiles.registerSelf(draft(), citizenSubject);
        Map<Who, String> tokens = Map.of(
                Who.CITIZEN, TestTokens.citizen(citizenSubject),
                Who.OFFICER, TestTokens.officer("matrix-officer"),
                Who.REVIEWER, TestTokens.reviewer("matrix-reviewer"),
                Who.ADMIN, TestTokens.admin("matrix-admin"),
                Who.DEPARTMENT,
                        TestTokens.department(
                                "matrix-dept",
                                // V199 repointed five journey categories to real sources; scope the department
                                // caller for both the remaining mock sources and the new real ones.
                                "revenue-rest-mock", "education-soap-mock", "dbt-rest-mock",
                                "dept-income-rest", "dept-marks-soap", "dept-bank-rest",
                                "dept-property-sftp", "dept-pollution-jdbc"));

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String route : liveRoutes()) {
            Set<Who> allowed = ApiAccessMatrix.ALLOWED.getOrDefault(route, Set.of());
            for (Who who : Who.values()) {
                int status = call(route, tokens.get(who), ownCitizen);
                checked++;
                boolean ok;
                String expected;
                if (who == Who.ANONYMOUS) {
                    ok = status == 401;
                    expected = "401";
                } else if (allowed.contains(who)) {
                    ok = status != 401 && status != 403 && status < 500;
                    expected = "not 401/403/5xx";
                } else {
                    ok = status == 403;
                    expected = "403";
                }
                if (!ok) {
                    failures.add(route + " as " + who + ": got " + status + ", expected " + expected);
                }
            }
        }
        assertThat(checked).isEqualTo(liveRoutes().size() * Who.values().length);
        assertThat(failures).as("access-matrix violations").isEmpty();
    }

    private int call(String route, String token, UUID ownCitizen) {
        String method = route.substring(0, route.indexOf(' '));
        String pattern = route.substring(route.indexOf(' ') + 1);
        String path = fill(pattern, ownCitizen) + query(pattern, ownCitizen);
        RestClient.RequestBodySpec req = RestClient.create()
                .method(HttpMethod.valueOf(method))
                .uri("http://localhost:" + port + path);
        if (token != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token));
        }
        if (!"GET".equals(method)) {
            req = req.contentType(MediaType.APPLICATION_JSON).body(body(ownCitizen));
        }
        return req.exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static String fill(String pattern, UUID ownCitizen) {
        Matcher m = PATH_VAR.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = switch (name) {
                case "citizenId" -> ownCitizen.toString();
                case "id" -> pattern.startsWith("/api/identity/citizens/") ? ownCitizen.toString() : UUID.randomUUID().toString();
                case "code" -> "POST_MATRIC_SCHOLARSHIP";
                case "referenceNo" -> "MH-MATRIX-000000";
                case "ref" -> "matrix-none@1";
                case "seq" -> "999999999";
                case "dataSourceCode" -> "matrix-none";
                default -> throw new IllegalStateException("no dummy value for path variable {" + name + "} in " + pattern);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String query(String pattern, UUID ownCitizen) {
        return switch (pattern) {
            case "/api/applications" -> "?citizenId=" + ownCitizen;
            case "/api/identity/citizens/{id}/connect-accounts" -> "?journeyCode=POST_MATRIC_SCHOLARSHIP";
            case "/api/connector/issued-documents" -> "?departmentCode=REVENUE";
            default -> "";
        };
    }

    /** One body that satisfies every POST well enough to get past validation-free security checks. */
    private static String body(UUID ownCitizen) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return """
                {"citizenId":"%s","departmentCode":"REVENUE","localIdType":"RATION","localId":"MATRIX-%s",
                 "provider":"DIGILOCKER","proof":"sandbox","requesterId":"SCHOLARSHIP",
                 "purposeCode":"SCHOLARSHIP_ELIGIBILITY","purposeText":"matrix","categories":["INCOME_CERTIFICATE"],
                 "note":"matrix","reason":"matrix","submission":{}}
                """.formatted(ownCitizen, unique);
    }

    private Set<String> liveRoutes() {
        Set<String> routes = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (!p.startsWith("/api/")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    throw new IllegalStateException("API route without an explicit HTTP method: " + p);
                }
                methods.forEach(m -> routes.add(m.name() + " " + p));
            }
        });
        return routes;
    }

    private static ProfileDraft draft() {
        return new ProfileDraft(
                "Matrix Citizen", "मॅट्रिक्स", "Matrix", "Citizen", "Father", LocalDate.of(2001, 2, 3), "DAY", "F", "90****00");
    }
}
