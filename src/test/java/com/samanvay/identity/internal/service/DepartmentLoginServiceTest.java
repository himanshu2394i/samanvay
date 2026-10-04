package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.internal.proof.DepartmentLoginStates;
import com.samanvay.shared.InvalidRequestException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Starting a department login: the citizen's browser is sent to the department with a one-time state, and only ever returns to Samanvay. */
class DepartmentLoginServiceTest {

    static final String RETURN_TO = "http://localhost:8080/shared/dept-callback.html";
    final List<String> issued = new ArrayList<>();

    DepartmentCatalog catalog(String loginUrl) {
        return new DepartmentCatalog() {
            public Optional<Department> byCode(String c) { return Optional.empty(); }
            public List<Department> all() { return List.of(); }
            public Department register(DepartmentDraft d) { throw new UnsupportedOperationException(); }
            public Optional<DepartmentIdentity> identity(String code) {
                return "REVENUE".equals(code) && loginUrl != null
                        ? Optional.of(new DepartmentIdentity("REVENUE_PERSON_ID", loginUrl, "https://rev.example.gov/.well-known/jwks.json", "dept:REVENUE"))
                        : Optional.empty();
            }
        };
    }

    DepartmentLoginStates states() {
        return new DepartmentLoginStates() {
            public LoginState issue(UUID citizenId, String departmentCode) {
                issued.add(citizenId + "|" + departmentCode);
                return new LoginState("ST" + issued.size(), "NN" + issued.size());
            }

            public boolean consume(UUID c, String d, String s, String n) { return false; }
        };
    }

    DepartmentLoginService service(String loginUrl, String... allowedPrefixes) {
        return new DepartmentLoginService(catalog(loginUrl), states(), List.of(allowedPrefixes));
    }

    static String param(String url, String name) {
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            if (kv.startsWith(name + "=")) {
                return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void the_citizen_is_sent_to_the_departments_login_with_return_address_state_and_nonce() {
        UUID citizen = UUID.randomUUID();
        String url = service("https://rev.example.gov/login", "http://localhost:8080/").startLogin(citizen, "REVENUE", RETURN_TO);
        assertThat(url).startsWith("https://rev.example.gov/login?");
        assertThat(param(url, "return_to")).isEqualTo(RETURN_TO);
        assertThat(param(url, "state")).isEqualTo("ST1");
        assertThat(param(url, "nonce")).isEqualTo("NN1");
        assertThat(issued).containsExactly(citizen + "|REVENUE");
    }

    @Test
    void a_login_url_that_already_has_a_query_gets_the_parameters_appended() {
        String url = service("https://rev.example.gov/login?lang=mr", "http://localhost:8080/").startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO);
        assertThat(url).startsWith("https://rev.example.gov/login?lang=mr&return_to=");
    }

    @Test
    void a_department_that_publishes_no_login_cannot_be_started() {
        assertThatThrownBy(() -> service(null, "http://localhost:8080/").startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service("https://x/login", "http://localhost:8080/").startLogin(UUID.randomUUID(), "UNKNOWN", RETURN_TO))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(issued).isEmpty();
    }

    @Test
    void a_return_address_outside_the_allow_list_is_refused_and_nothing_is_issued() {
        var s = service("https://rev.example.gov/login", "http://localhost:8080/");
        for (String bad : new String[] {"https://evil.example/cb", "http://localhost:8080.evil.example/cb", "http://localhost:8081/cb", "", "  ", null,
            "javascript:alert(1)"}) {
            assertThatThrownBy(() -> s.startLogin(UUID.randomUUID(), "REVENUE", bad)).as("returnTo=" + bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(issued).isEmpty();
    }

    @Test
    void a_path_prefix_matches_only_on_a_path_boundary_not_as_a_string_prefix() {
        var s = service("https://rev.example.gov/login", "http://localhost:8080/shared");
        assertThat(s.startLogin(UUID.randomUUID(), "REVENUE", "http://localhost:8080/shared/cb")).contains("return_to=");
        assertThat(s.startLogin(UUID.randomUUID(), "REVENUE", "http://localhost:8080/shared")).contains("return_to=");
        for (String bad : new String[] {"http://localhost:8080/shared-evil/cb", "http://localhost:8080/sharedx", "http://localhost:8080/shared/../admin",
            "http://localhost:8080/shared/%2e%2e/admin", "http://localhost:8080/shared/%2E%2E%2Fadmin", "http://localhost:8080@evil.example/shared/cb",
            "https://localhost:8080/shared/cb", "http://localhost:80/shared/cb", "http://localhost:8080/shared\\..\\admin"}) {
            assertThatThrownBy(() -> s.startLogin(UUID.randomUUID(), "REVENUE", bad)).as("returnTo=" + bad).isInstanceOf(InvalidRequestException.class);
        }
        var bare = service("https://rev.example.gov/login", "http://localhost:8080");
        assertThat(bare.startLogin(UUID.randomUUID(), "REVENUE", "http://localhost:8080/any/where")).contains("return_to=");
        assertThatThrownBy(() -> bare.startLogin(UUID.randomUUID(), "REVENUE", "http://localhost:80801/cb")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void with_no_allow_list_configured_every_return_address_is_refused_fail_closed() {
        assertThatThrownBy(() -> service("https://rev.example.gov/login").startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void a_login_url_that_is_not_http_or_https_is_refused() {
        assertThatThrownBy(() -> service("javascript:alert(1)", "http://localhost:8080/").startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service("ftp://rev.example.gov/login", "http://localhost:8080/").startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(issued).isEmpty();
    }

    @Test
    void two_starts_get_different_states() {
        var s = service("https://rev.example.gov/login", "http://localhost:8080/");
        String a = param(s.startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO), "state");
        String b = param(s.startLogin(UUID.randomUUID(), "REVENUE", RETURN_TO), "state");
        assertThat(a).isNotEqualTo(b);
    }
}
